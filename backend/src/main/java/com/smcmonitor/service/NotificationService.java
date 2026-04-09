package com.smcmonitor.service;

import com.smcmonitor.model.Signal;
import com.smcmonitor.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender mailSender;

    @Value("${smc.alert.email-enabled:false}") private boolean emailEnabled;
    @Value("${spring.mail.username:}")         private String  fromAddress;

    @Async
    public void onSignal(User user, Signal sig) {
        if (!emailEnabled || !user.getSettings().isNotifOn()) return;

        String subject = String.format("🚨 SMC Signal: %s %s | Score %.1f | %s",
            sig.getPair(), sig.isBull() ? "▲ BUY" : "▼ SELL", sig.getScore(), sig.getGrade());

        String body = String.format("""
            New SMC v15 Signal Detected
            ─────────────────────────────
            Pair     : %s
            Direction: %s
            Score    : %.1f / 20 (%s)
            Session  : %s  |  Regime: %s

            Entry    : %s
            SL (%s)  : %s
            TP1 (2R) : %s
            TP2      : %s

            Flags    : %s%s%s%s%s
            ─────────────────────────────
            IDM Conf : %s  |  OTE: %s
            Vol Quiet: %s (ratio %.2f×)
            """,
            sig.getPair(), sig.isBull() ? "▲ BUY" : "▼ SELL",
            sig.getScore(), sig.getGrade(), sig.getSession(), sig.getRegime(),
            sig.getEntry(), sig.getSlMethod(), sig.getSl(), sig.getTp1(), sig.getTp2(),
            sig.isSweep()   ? "★Sweep " : "",
            sig.isChoch()   ? "CHoCH "  : "",
            sig.isHasOB()   ? "OB "     : "",
            sig.isHasFVG()  ? "FVG "    : "",
            sig.isBreaker() ? "Breaker " : "",
            sig.isIdmConfirmed() ? "✓ Yes" : "✗ No",
            sig.isInOTE()        ? "✓ Yes" : "–",
            sig.isVolumeQuiet()  ? "⚠ LOW" : "✓ OK", sig.getBodyRatio()
        );
        send(user.getEmail(), subject, body);
    }

    @Async
    public void onAutoClose(User user, Signal sig, String outcome, double pnl, String reason) {
        if (!emailEnabled || !user.getSettings().isNotifOn()) return;
        String emoji   = "win".equals(outcome) ? "✅" : "partial".equals(outcome) ? "🟡" : "❌";
        String subject = String.format("%s Auto-Close: %s %s → %s | P&L $%.2f",
            emoji, sig.getPair(), sig.isBull() ? "BUY" : "SELL", outcome.toUpperCase(), pnl);
        String body = String.format("""
            Server-Side Auto-Close Executed
            ─────────────────────────────
            Pair     : %s  %s
            Outcome  : %s
            Reason   : %s
            P&L      : %s$%.2f
            Balance  : $%.2f
            """,
            sig.getPair(), sig.isBull() ? "▲ BUY" : "▼ SELL",
            outcome.toUpperCase(), reason != null ? reason : "–",
            pnl >= 0 ? "+" : "", pnl, user.getBalance());
        send(user.getEmail(), subject, body);
    }

    private void send(String to, String subject, String body) {
        try {
            var msg = new SimpleMailMessage();
            msg.setFrom(fromAddress); msg.setTo(to);
            msg.setSubject(subject);  msg.setText(body);
            mailSender.send(msg);
            log.info("Email sent → {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Email failed: {}", e.getMessage());
        }
    }
}
