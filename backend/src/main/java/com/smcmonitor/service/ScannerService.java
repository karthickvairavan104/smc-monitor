package com.smcmonitor.service;

import com.smcmonitor.model.Signal;
import com.smcmonitor.model.User;
import com.smcmonitor.repository.SignalRepository;
import com.smcmonitor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerService {

    private final SignalRepository       signalRepo;
    private final UserRepository         userRepo;
    private final ScoringService         scoring;
    private final NotificationService    notifications;
    private final SimpMessagingTemplate  ws;          // WebSocket push
    private final WebClient.Builder      webClientBuilder;

    @Value("${smc.scan.min-score:6}")
    private double minScore;

    static final Map<String, String> YAHOO = Map.ofEntries(
        Map.entry("EUR/USD","EURUSD=X"), Map.entry("GBP/USD","GBPUSD=X"),
        Map.entry("USD/JPY","USDJPY=X"), Map.entry("USD/CHF","USDCHF=X"),
        Map.entry("AUD/USD","AUDUSD=X"), Map.entry("USD/CAD","USDCAD=X"),
        Map.entry("NZD/USD","NZDUSD=X"), Map.entry("EUR/GBP","EURGBP=X"),
        Map.entry("EUR/JPY","EURJPY=X"), Map.entry("GBP/JPY","GBPJPY=X"),
        Map.entry("EUR/AUD","EURAUD=X"), Map.entry("AUD/JPY","AUDJPY=X"),
        Map.entry("CAD/JPY","CADJPY=X"), Map.entry("XAU/USD","GC=F"),
        Map.entry("NAS100","NQ=F")
    );

    private final Map<String, CachedCandles> candleCache = new ConcurrentHashMap<>();
    record CachedCandles(List<ScoringService.Candle> candles, long fetchedAt) {}

    // ── Scheduled scan every 60s ─────────────────────────────────────────
    @Scheduled(fixedRateString = "${smc.scan.interval-ms:60000}")
    public void scanAllPairs() {
        if (!isForexOpen()) { log.debug("Market closed — skipping scan"); return; }

        log.info("Starting market scan ({} pairs)", YAHOO.size());
        long t0 = System.currentTimeMillis();

        List<User> users = userRepo.findAll();
        if (users.isEmpty()) return;

        Set<String> allPairs = users.stream()
            .flatMap(u -> u.getSettings().getWatchPairs().stream())
            .collect(Collectors.toSet());

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = allPairs.stream()
                .map(pair -> CompletableFuture.runAsync(() -> scanOnePair(pair, users), vt))
                .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(50, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            log.error("Scan error: {}", e.getMessage());
        }
        log.info("Scan complete in {}ms", System.currentTimeMillis() - t0);
    }

    private void scanOnePair(String pair, List<User> users) {
        try {
            List<ScoringService.Candle> candles = getCachedCandles(pair);
            if (candles == null || candles.size() < 40) return;

            Signal signal = analyzeAndScore(pair, candles);
            if (signal == null || signal.getScore() < minScore) return;

            for (User user : users) {
                if (!user.getSettings().getWatchPairs().contains(pair)) continue;
                if (signal.getScore() < user.getSettings().getAlertScore()) continue;

                Signal saved = signalRepo.save(cloneForUser(signal, user.getId()));

                // Push via WebSocket — frontend receives immediately, no polling needed
                ws.convertAndSend("/topic/signals/" + user.getId(), saved);

                notifications.onSignal(user, saved);
                log.info("Signal: {} {} score={:.1f} user={}", pair,
                    signal.isBull() ? "BUY" : "SELL", signal.getScore(), user.getEmail());
            }
        } catch (Exception e) {
            log.warn("Failed to scan {}: {}", pair, e.getMessage());
        }
    }

    // ── Full v15 analysis ─────────────────────────────────────────────────
    Signal analyzeAndScore(String pair, List<ScoringService.Candle> cs) {
        double[]  atr    = scoring.calcATR(cs, 14);
        String[]  ltf    = scoring.calcLTF(cs, 15);
        String[]  htf    = scoring.calcHTF(cs, 12);
        String[]  pd     = scoring.calcPD(cs, 48);
        String    regime = scoring.calcRegime(cs, atr);

        List<ScoringService.OB>       obs      = scoring.detectOBs(cs, atr, ltf);
        List<ScoringService.OB>       fvgs     = scoring.detectFVGs(cs);
        List<ScoringService.LiqLevel> liqs     = scoring.detectLiqs(cs, 12);
        List<ScoringService.OB>       breakers = scoring.detectBreakerBlocks(cs, obs, atr);

        int    i      = cs.size() - 1;
        ScoringService.Candle c = cs.get(i);
        double curATR = atr[i] > 0 ? atr[i] : 0.0001;
        double buf    = curATR * 0.15;

        double[] sortedAtr = Arrays.stream(atr).filter(v -> v > 0).sorted().toArray();
        double atrThresh   = sortedAtr.length > 0 ? sortedAtr[(int)(sortedAtr.length * 0.2)] : 0;

        ScoringService.VolumeGate vg = scoring.calcVolumeGate(cs);

        // Zone detection (same logic as JSX scanPair)
        ScoringService.OB ob      = findActiveZone(obs,      cs, i, buf, null);
        ScoringService.OB fvg     = findActiveFVG(fvgs,     cs, i, buf);
        ScoringService.OB breaker = findActiveZone(breakers, cs, i, buf, "breaker");

        if (ob == null && fvg == null && breaker == null) return null;

        boolean isBull = breaker != null ? "bull".equals(breaker.type())
                       : ob      != null ? "bull".equals(ob.type())
                       :                   "bull".equals(fvg.type());

        ScoringService.OB activeZone = breaker != null ? breaker : ob != null ? ob : fvg;

        boolean hasOB    = ob != null || breaker != null;
        boolean hasFVG   = fvg != null;
        boolean isBreaker= breaker != null;
        ScoringService.OB obRef = ob != null ? ob : breaker;

        boolean sweep   = scoring.hasSweep(cs, i, isBull, liqs, atr);
        boolean choch   = scoring.hasCHoCH(cs, i, isBull);
        boolean strongM = scoring.isInstitutional(cs, Math.max(0, i-1), atr);
        boolean ccOk    = scoring.hasCandleClose(cs, i, obRef);

        ScoringService.IDMResult  idm     = hasOB ? scoring.detectIDM(cs, obRef, liqs, i)
                                                   : new ScoringService.IDMResult(true, null);
        ScoringService.OTEResult  ote     = scoring.detectOTE(cs, i, isBull);
        ScoringService.ZoneAge    zAge    = obRef != null ? scoring.calcZoneAge(obRef, i) : null;

        String session = currentSession();

        ScoringService.ScoreResult sv = scoring.score(
            isBull, ltf[i], htf[i],
            hasOB, obRef != null && obRef.instForm(), obRef != null && obRef.bos(),
                   obRef != null && obRef.strong(),
            hasFVG, sweep, choch, session, false, strongM,
            pd[i], regime, curATR, atrThresh,
            idm.confirmed(), ote.inOTE(),
            zAge != null ? zAge.ageScore() : 0,
            isBreaker, ccOk, vg.quiet(), 0
        );

        if (sv.total() < minScore) return null;

        double entry = obRef != null ? obRef.eq() : c.close();
        ScoringService.SmartSLResult ssl = scoring.calcSmartSL(liqs, isBull, entry, curATR, pair);
        double slDist = Math.abs(entry - ssl.sl());
        double tp1    = isBull ? entry + slDist * ScoringService.TP1_RR
                               : entry - slDist * ScoringService.TP1_RR;
        ScoringService.HTFLiqResult htfLiq = scoring.findHTFLiq(liqs, i, isBull, entry, slDist);
        double tp2Base = htfLiq.htfTP() != null ? htfLiq.htfTP()
                       : isBull ? entry + slDist * ScoringService.TP2_TREND
                                : entry - slDist * ScoringService.TP2_TREND;
        double tp2 = "ranging".equals(regime)
            ? (isBull ? entry + slDist * ScoringService.TP2_RANGE : entry - slDist * ScoringService.TP2_RANGE)
            : tp2Base;

        int dp = (pair.contains("JPY") || "XAU/USD".equals(pair) || "NAS100".equals(pair)) ? 2 : 4;
        double f = Math.pow(10, dp);

        return Signal.builder()
            .pair(pair).isBull(isBull)
            .entry(Math.round(entry * f) / f)
            .sl(Math.round(ssl.sl() * f) / f)
            .tp1(Math.round(tp1 * f) / f)
            .tp2(Math.round(tp2 * f) / f)
            .rr(htfLiq.htfRR())
            .score(sv.total()).grade(sv.grade())
            .breakdown(sv.bd()).regime(sv.regime())
            .ltfDir(ltf[i]).htfDir(htf[i]).pdZone(pd[i]).session(session)
            .sweep(sweep).choch(choch).strongMom(strongM)
            .hasOB(hasOB).hasFVG(hasFVG).isBreaker(isBreaker)
            .idmConfirmed(idm.confirmed())
            .inOTE(ote.inOTE()).oteFibPct(ote.fibPct())
            .zoneAgeLabel(zAge != null ? zAge.label() : "–")
            .htfLiqLabel(htfLiq.label())
            .candleClose(ccOk)
            .slMethod(ssl.method()).slLiqLevel(ssl.liqLevel())
            .volumeQuiet(vg.quiet()).bodyRatio(vg.ratio())
            .curATR(curATR).status("LIVE")
            .build();
    }

    // ── Zone finders ──────────────────────────────────────────────────────
    private ScoringService.OB findActiveZone(List<ScoringService.OB> zones,
                                              List<ScoringService.Candle> cs,
                                              int i, double buf, String kind) {
        ScoringService.Candle c = cs.get(i);
        for (ScoringService.OB z : zones) {
            if (z.idx() >= i || z.idx() < i - 20) continue;
            if ("bull".equals(z.type())) {
                if (c.low() <= z.top() + buf && c.close() >= z.bot() - buf) return z;
            } else {
                if (c.high() >= z.bot() - buf && c.close() <= z.top() + buf) return z;
            }
        }
        return null;
    }

    private ScoringService.OB findActiveFVG(List<ScoringService.OB> fvgs,
                                             List<ScoringService.Candle> cs,
                                             int i, double buf) {
        ScoringService.Candle c = cs.get(i);
        for (ScoringService.OB f : fvgs) {
            if (f.idx() >= i || f.idx() < i - 12) continue;
            if (c.close() >= f.bot() - buf && c.close() <= f.top() + buf) return f;
        }
        return null;
    }

    // ── Yahoo Finance fetch ───────────────────────────────────────────────
    private List<ScoringService.Candle> getCachedCandles(String pair) {
        CachedCandles cached = candleCache.get(pair);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < 3_600_000)
            return cached.candles();
        List<ScoringService.Candle> fresh = fetchCandles(pair, 100);
        if (fresh != null && !fresh.isEmpty())
            candleCache.put(pair, new CachedCandles(fresh, System.currentTimeMillis()));
        return fresh;
    }

    @SuppressWarnings("unchecked")
    List<ScoringService.Candle> fetchCandles(String pair, int bars) {
        String sym = YAHOO.get(pair); if (sym == null) return Collections.emptyList();
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + sym + "?interval=1h&range=30d";
            Map<?,?> body = webClientBuilder.build().get().uri(url)
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(8));
            if (body == null) return Collections.emptyList();

            Map<?,?> chart   = (Map<?,?>) body.get("chart");
            List<?> results  = (List<?>) chart.get("result");
            if (results == null || results.isEmpty()) return Collections.emptyList();

            Map<?,?> result    = (Map<?,?>) results.get(0);
            List<?> timestamps = (List<?>) result.get("timestamp");
            Map<?,?> indicators= (Map<?,?>) result.get("indicators");
            Map<?,?> quote     = (Map<?,?>) ((List<?>) indicators.get("quote")).get(0);
            List<?> opens  = (List<?>) quote.get("open");
            List<?> highs  = (List<?>) quote.get("high");
            List<?> lows   = (List<?>) quote.get("low");
            List<?> closes = (List<?>) quote.get("close");

            List<ScoringService.Candle> out = new ArrayList<>();
            for (int k = 0; k < timestamps.size(); k++) {
                Number o = (Number)opens.get(k), h = (Number)highs.get(k),
                       l = (Number)lows.get(k),  cl = (Number)closes.get(k);
                if (o==null||h==null||l==null||cl==null) continue;
                long ts = ((Number)timestamps.get(k)).longValue() * 1000L;
                int  uh = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).getHour();
                String ses = uh>=7&&uh<10?"London":uh>=13&&uh<16?"NewYork":uh<3?"Asia":"Off";
                out.add(new ScoringService.Candle(out.size(),
                    r6(o.doubleValue()), r6(h.doubleValue()), r6(l.doubleValue()), r6(cl.doubleValue()),
                    ts, true, ses));
            }
            return out.subList(Math.max(0, out.size() - bars), out.size());
        } catch (Exception e) {
            log.warn("Yahoo fetch failed for {}: {}", pair, e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public Double fetchLivePrice(String pair) {
        String sym = YAHOO.get(pair); if (sym == null) return null;
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + sym + "?interval=1m&range=5m";
            Map<?,?> body = webClientBuilder.build().get().uri(url)
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(5));
            if (body == null) return null;
            Map<?,?> chart  = (Map<?,?>) body.get("chart");
            List<?> results = (List<?>) chart.get("result");
            if (results == null || results.isEmpty()) return null;
            Map<?,?> q = (Map<?,?>) ((List<?>) ((Map<?,?>) ((Map<?,?>) results.get(0))
                .get("indicators")).get("quote")).get(0);
            List<?> cls = (List<?>) q.get("close");
            for (int k = cls.size()-1; k >= 0; k--)
                if (cls.get(k) != null) return ((Number) cls.get(k)).doubleValue();
            return null;
        } catch (Exception e) { return null; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private boolean isForexOpen() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int day = now.getDayOfWeek().getValue(); // Mon=1…Sun=7
        int hm  = now.getHour() * 60 + now.getMinute();
        return !(day == 6 && hm >= 22*60) && !(day == 7 && hm < 22*60);
    }

    private String currentSession() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int hm = now.getHour() * 60 + now.getMinute();
        if (hm >= 7*60  && hm < 10*60) return "London";
        if (hm >= 13*60 && hm < 16*60) return "NewYork";
        if (hm < 3*60)                  return "Asia";
        return "Off-Session";
    }

    private Signal cloneForUser(Signal s, String userId) {
        Signal c = new Signal();
        c.setUserId(userId);
        c.setPair(s.getPair());           c.setBull(s.isBull());
        c.setEntry(s.getEntry());         c.setSl(s.getSl());
        c.setTp1(s.getTp1());             c.setTp2(s.getTp2()); c.setRr(s.getRr());
        c.setScore(s.getScore());         c.setGrade(s.getGrade());
        c.setBreakdown(s.getBreakdown()); c.setRegime(s.getRegime());
        c.setLtfDir(s.getLtfDir());       c.setHtfDir(s.getHtfDir());
        c.setPdZone(s.getPdZone());       c.setSession(s.getSession());
        c.setSweep(s.isSweep());          c.setChoch(s.isChoch());
        c.setHasOB(s.isHasOB());          c.setHasFVG(s.isHasFVG());
        c.setBreaker(s.isBreaker());
        c.setIdmConfirmed(s.isIdmConfirmed());
        c.setInOTE(s.isInOTE());          c.setOteFibPct(s.getOteFibPct());
        c.setZoneAgeLabel(s.getZoneAgeLabel());
        c.setHtfLiqLabel(s.getHtfLiqLabel());
        c.setCandleClose(s.isCandleClose());
        c.setSlMethod(s.getSlMethod());   c.setSlLiqLevel(s.getSlLiqLevel());
        c.setVolumeQuiet(s.isVolumeQuiet()); c.setBodyRatio(s.getBodyRatio());
        c.setCurATR(s.getCurATR());       c.setStatus("LIVE");
        return c;
    }

    private double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
