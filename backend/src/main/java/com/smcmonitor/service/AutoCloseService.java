package com.smcmonitor.service;

import com.smcmonitor.model.Signal;
import com.smcmonitor.model.Trade;
import com.smcmonitor.model.User;
import com.smcmonitor.repository.SignalRepository;
import com.smcmonitor.repository.TradeRepository;
import com.smcmonitor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoCloseService {

    private final SignalRepository    signalRepo;
    private final TradeRepository     tradeRepo;
    private final UserRepository      userRepo;
    private final ScannerService      scanner;
    private final NotificationService notifications;
    private final SimpMessagingTemplate ws;

    private static final long MAX_TRADE_MS = 48L * 3_600_000;

    @Scheduled(fixedRateString = "${smc.scan.price-check-ms:30000}")
    public void checkOpenPositions() {
        List<Signal> live = signalRepo.findByStatusAndCreatedAtAfter(
            "LIVE", Instant.now().minusMillis(MAX_TRADE_MS + 60_000)
        );
        if (live.isEmpty()) return;
        log.debug("Auto-close check: {} open signals", live.size());

        for (Signal sig : live) {
            try {
                // Timeout check (no price fetch needed)
                long ageMs = Instant.now().toEpochMilli() - sig.getCreatedAt().toEpochMilli();
                if (ageMs >= MAX_TRADE_MS) {
                    closeSignal(sig, "timeout", "48h timeout", 0.0);
                    continue;
                }

                Double price = scanner.fetchLivePrice(sig.getPair());
                if (price == null) continue;

                String outcome = null, reason = null;
                if (sig.isBull()) {
                    if      (price <= sig.getSl())  { outcome = "loss";    reason = "SL hit @ "  + price; }
                    else if (price >= sig.getTp2()) { outcome = "win";     reason = "TP2 hit @ " + price; }
                    else if (price >= sig.getTp1()) { outcome = "partial"; reason = "TP1 hit @ " + price; }
                } else {
                    if      (price >= sig.getSl())  { outcome = "loss";    reason = "SL hit @ "  + price; }
                    else if (price <= sig.getTp2()) { outcome = "win";     reason = "TP2 hit @ " + price; }
                    else if (price <= sig.getTp1()) { outcome = "partial"; reason = "TP1 hit @ " + price; }
                }

                if (outcome != null) {
                    double rrMult = "win".equals(outcome) ? sig.getRr()
                                  : "partial".equals(outcome) ? sig.getRr() * 0.5
                                  : -1.0;
                    closeSignal(sig, outcome, reason, rrMult);
                }
            } catch (Exception e) {
                log.warn("Auto-close error for {}: {}", sig.getPair(), e.getMessage());
            }
        }
    }

    private void closeSignal(Signal sig, String outcome, String reason, double rrMult) {
        sig.setStatus("CLOSED");
        sig.setOutcome(outcome);
        signalRepo.save(sig);

        userRepo.findById(sig.getUserId()).ifPresent(user -> {
            double kelly   = calcKelly(user, sig.getScore());
            double riskAmt = user.getBalance() * kelly;
            double pnl     = Math.round(riskAmt * rrMult * 100.0) / 100.0;
            double newBal  = Math.round((user.getBalance() + pnl) * 100.0) / 100.0;

            user.setBalance(newBal);
            user.setPeakBalance(Math.max(user.getPeakBalance(), newBal));
            if (user.getEquityCurve() != null) user.getEquityCurve().add(newBal);
            userRepo.save(user);

            Trade trade = Trade.builder()
                .userId(sig.getUserId())
                .pair(sig.getPair()).isBull(sig.isBull())
                .entry(sig.getEntry()).sl(sig.getSl()).tp1(sig.getTp1()).tp2(sig.getTp2())
                .score(sig.getScore()).grade(sig.getGrade())
                .regime(sig.getRegime()).session(sig.getSession())
                .idmConfirmed(sig.isIdmConfirmed()).inOTE(sig.isInOTE())
                .isBreaker(sig.isBreaker()).slMethod(sig.getSlMethod())
                .outcome(outcome)
                .pnl(pnl)
                .kellyPct(Math.round(kelly * 10000.0) / 100.0)
                .autoClose(true).closeReason(reason)
                .balanceAfter(newBal).closedAt(Instant.now())
                .build();
            tradeRepo.save(trade);

            // Push auto-close event via WebSocket so the dashboard updates instantly
            ws.convertAndSend("/topic/autoclose/" + user.getId(), Map.of(
                "signalId", sig.getId(),
                "pair",     sig.getPair(),
                "outcome",  outcome,
                "reason",   reason != null ? reason : "",
                "pnl",      pnl,
                "balance",  newBal
            ));

            notifications.onAutoClose(user, sig, outcome, pnl, reason);
            log.info("Auto-closed: {} {} → {} PnL=${} user={}",
                sig.getPair(), sig.isBull() ? "BUY" : "SELL",
                outcome.toUpperCase(), pnl, user.getEmail());
        });
    }

    // Manual close called by SignalController
    public Trade manualClose(Signal sig, String outcome, User user) {
        double rrMult = "win".equals(outcome) ? sig.getRr()
                      : "partial".equals(outcome) ? sig.getRr() * 0.5
                      : "timeout".equals(outcome) ? 0.0 : -1.0;
        closeSignal(sig, outcome, "Manual close", rrMult);
        return tradeRepo.findByUserIdOrderByCreatedAtDesc(user.getId())
            .stream().findFirst().orElse(null);
    }

    private double calcKelly(User user, double score) {
        // Uses live win-rate if trades exist, otherwise default 50% WR assumption
        List<Trade> trades = tradeRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        long wins   = trades.stream().filter(t -> "win".equals(t.getOutcome())).count();
        long closed = trades.stream()
            .filter(t -> t.getOutcome() != null && !"timeout".equals(t.getOutcome())).count();
        double wr = closed > 10 ? (double) wins / closed : 0.50;

        double p = Math.max(wr, 0.35), q = 1 - p;
        double b = Math.max(sig_avgRR(trades), 0.5);
        double fullKelly = Math.max((p * b - q) / b, 0);
        double halfKelly = fullKelly * 0.5;
        double scaled    = halfKelly * (score / 20.0);
        return Math.min(scaled, 0.03);
    }

    private double sig_avgRR(List<Trade> trades) {
        return trades.stream()
            .filter(t -> "win".equals(t.getOutcome()) || "partial".equals(t.getOutcome()))
            .mapToDouble(t -> t.getPnl() > 0 ? t.getPnl() : 0)
            .average().orElse(100) /
            Math.max(trades.stream()
            .filter(t -> "loss".equals(t.getOutcome()))
            .mapToDouble(t -> Math.abs(t.getPnl()))
            .average().orElse(50), 1);
    }
}
