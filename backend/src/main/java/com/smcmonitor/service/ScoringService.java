package com.smcmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Complete Java port of SMC v15 scoring engine.
 * Mirrors every detector and weight from the frontend JSX exactly.
 */
@Slf4j
@Service
public class ScoringService {

    // ── Weights (mirrors WEIGHTS in v15 JSX) ────────────────────────────
    static final double W_BOS           = 2.0;
    static final double W_CHOCH         = 3.0;
    static final double W_FVG           = 2.0;
    static final double W_OB            = 2.0;
    static final double W_MTF           = 3.0;
    static final double W_MTF_WEAK      = 1.5;
    static final double W_SWEEP         = 2.0;
    static final double W_SESSION       = 1.0;
    static final double W_NEWS          = 1.0;
    static final double W_MOMENTUM      = 1.0;
    static final double W_IDM_OK        = 2.0;
    static final double W_IDM_MISS      = -2.0;
    static final double W_OTE           = 1.5;
    static final double W_FRESH         = 1.0;
    static final double W_DECAY         = -0.5;
    static final double W_BREAKER       = 2.5;
    static final double W_OB_SES        = 0.8;
    static final double W_PD            = 1.0;
    static final double W_VOL_QUIET     = -2.0;
    static final double MAX_SCORE       = 20.0;
    static final double OTE_FIB_LOW     = 0.62;
    static final double OTE_FIB_HIGH    = 0.79;
    static final int    OB_GOLDEN_AGE   = 8;
    static final int    OB_MAX_AGE      = 50;
    static final int    VOL_MA_PERIOD   = 20;
    static final int    SMART_BUF_PIPS  = 5;
    static final double TP1_RR          = 2.0;
    static final double TP2_TREND       = 2.5;
    static final double TP2_RANGE       = 2.0;

    // ── Data records ─────────────────────────────────────────────────────
    public record Candle(int idx, double open, double high, double low, double close,
                         long ts, boolean real, String obSes) {}
    public record LiqLevel(int idx, String type, double price) {}  // "high"|"low"
    public record OB(int idx, String type, double top, double bot, double eq,
                     boolean strong, boolean bos, String formSes, boolean instForm,
                     boolean isBreakerBlock) {}
    public record ScoreResult(double total, Map<String, Double> bd,
                              double obBonus, String regime, String grade) {}
    public record SmartSLResult(double sl, String method, Double liqLevel) {}
    public record VolumeGate(boolean quiet, double ratio) {}
    public record IDMResult(boolean confirmed, Integer sweepCandle) {}
    public record OTEResult(boolean inOTE, double fibPct, double fibHigh, double fibLow) {}
    public record ZoneAge(double ageScore, int ageBars, String label) {}
    public record HTFLiqResult(Double htfTP, double htfRR, String label) {}

    // ─────────────────────────────────────────────────────────────────────
    //  INDICATORS
    // ─────────────────────────────────────────────────────────────────────

    public double[] calcATR(List<Candle> cs, int period) {
        double[] atr = new double[cs.size()];
        for (int i = 1; i < cs.size(); i++) {
            Candle c = cs.get(i), p = cs.get(i - 1);
            double tr = Math.max(c.high() - c.low(),
                        Math.max(Math.abs(c.high() - p.close()),
                                 Math.abs(c.low()  - p.close())));
            atr[i] = i < period ? tr : (atr[i-1] * (period-1) + tr) / period;
        }
        return atr;
    }

    public String[] calcLTF(List<Candle> cs, int lb) {
        String[] out = new String[cs.size()];
        Arrays.fill(out, "neutral");
        for (int i = lb; i < cs.size(); i++) {
            double pH = Double.MIN_VALUE, pL = Double.MAX_VALUE;
            for (int j = i - lb; j < i; j++) {
                pH = Math.max(pH, cs.get(j).high());
                pL = Math.min(pL, cs.get(j).low());
            }
            out[i] = cs.get(i).close() > pH ? "bull"
                   : cs.get(i).close() < pL ? "bear" : out[i-1];
        }
        return out;
    }

    public String[] calcHTF(List<Candle> cs, int period) {
        String[] out = new String[cs.size()];
        Arrays.fill(out, "neutral");
        List<double[]> htf = new ArrayList<>();   // {h, l, c}
        List<String> htfT  = new ArrayList<>();
        for (int i = 0; i + period <= cs.size(); i += period) {
            double h = Double.MIN_VALUE, l = Double.MAX_VALUE;
            for (int j = i; j < i + period; j++) {
                h = Math.max(h, cs.get(j).high());
                l = Math.min(l, cs.get(j).low());
            }
            htf.add(new double[]{h, l, cs.get(i + period - 1).close()});
        }
        for (int hi = 0; hi < htf.size(); hi++) {
            if (hi < 2) { htfT.add("neutral"); continue; }
            double[] b = htf.get(hi), pv = htf.get(hi - 1);
            htfT.add(b[2] > pv[0] ? "bull" : b[2] < pv[1] ? "bear" : htfT.get(hi - 1));
        }
        for (int hi = 0; hi < htf.size(); hi++) {
            String dir = hi < htfT.size() ? htfT.get(hi) : "neutral";
            for (int k = hi * period; k < Math.min((hi + 1) * period, cs.size()); k++)
                out[k] = dir;
        }
        return out;
    }

    public String[] calcPD(List<Candle> cs, int lb) {
        String[] out = new String[cs.size()];
        Arrays.fill(out, "eq");
        for (int i = lb; i < cs.size(); i++) {
            double hi = Double.MIN_VALUE, lo = Double.MAX_VALUE;
            for (int j = i - lb; j < i; j++) {
                hi = Math.max(hi, cs.get(j).high());
                lo = Math.min(lo, cs.get(j).low());
            }
            out[i] = cs.get(i).close() > (hi + lo) / 2 ? "premium" : "discount";
        }
        return out;
    }

    public String calcRegime(List<Candle> cs, double[] atr) {
        int n = cs.size();
        if (n < 30) return "unknown";
        double ra = 0, oa = 0;
        int rCount = 0, oCount = 0;
        for (int i = Math.max(0, n-10); i < n; i++) { ra += atr[i]; rCount++; }
        for (int i = Math.max(0, n-30); i < n-10; i++) { oa += atr[i]; oCount++; }
        ra = rCount > 0 ? ra / rCount : 0.001;
        oa = oCount > 0 ? oa / oCount : ra;
        double ratio = ra / (oa > 0 ? oa : ra);
        int up = 0, dn = 0;
        for (int i = n - 19; i < n; i++) {
            if (cs.get(i).close() > cs.get(i-1).close()) up++; else dn++;
        }
        double dir = Math.abs(up - dn) / 20.0;
        if (ratio > 1.5) return "volatile";
        if (dir   > 0.3) return "trending";
        return "ranging";
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SMC DETECTORS
    // ─────────────────────────────────────────────────────────────────────

    public boolean isInstitutional(List<Candle> cs, int idx, double[] atr) {
        if (idx >= cs.size()) return false;
        Candle imp = cs.get(idx);
        double body = Math.abs(imp.close() - imp.open());
        double ia   = atr[idx] > 0 ? atr[idx] : 0.0001;
        double avg  = 0; int cnt = 0;
        for (int k = Math.max(0, idx-8); k < idx; k++) {
            avg += Math.abs(cs.get(k).close() - cs.get(k).open()); cnt++;
        }
        avg = cnt > 0 ? avg / cnt : ia;
        return body >= ia * 1.8 && body >= avg * 1.4;
    }

    public List<OB> detectOBs(List<Candle> cs, double[] atr, String[] ltf) {
        List<OB> obs = new ArrayList<>();
        for (int i = 2; i < cs.size() - 3; i++) {
            Candle ob = cs.get(i), imp = cs.get(i + 1);
            boolean strong = isInstitutional(cs, i + 1, atr);
            String  fSes   = ob.obSes() != null ? ob.obSes() : "Off";
            boolean iF     = "London".equals(fSes) || "NewYork".equals(fSes);
            if (ob.close() < ob.open() && imp.close() > ob.high()) {
                boolean bos = "bull".equals(ltf[i+1]) && !"bull".equals(ltf[i]);
                obs.add(new OB(i, "bull", ob.high(), ob.low(), (ob.high()+ob.low())/2,
                               strong, bos, fSes, iF, false));
            }
            if (ob.close() > ob.open() && imp.close() < ob.low()) {
                boolean bos = "bear".equals(ltf[i+1]) && !"bear".equals(ltf[i]);
                obs.add(new OB(i, "bear", ob.high(), ob.low(), (ob.high()+ob.low())/2,
                               strong, bos, fSes, iF, false));
            }
        }
        return obs;
    }

    public List<OB> detectFVGs(List<Candle> cs) {
        List<OB> fvgs = new ArrayList<>();
        for (int i = 1; i < cs.size() - 1; i++) {
            Candle a = cs.get(i-1), cv = cs.get(i+1);
            if (cv.low()  > a.high()) fvgs.add(new OB(i,"bull",cv.low(), a.high(),(cv.low()+a.high())/2, false,false,"Off",false,false));
            if (cv.high() < a.low())  fvgs.add(new OB(i,"bear",a.low(),  cv.high(),(a.low()+cv.high())/2,false,false,"Off",false,false));
        }
        return fvgs;
    }

    public List<LiqLevel> detectLiqs(List<Candle> cs, int lb) {
        List<LiqLevel> liqs = new ArrayList<>();
        for (int i = lb; i < cs.size() - lb; i++) {
            boolean isH = true, isL = true;
            double cH = cs.get(i).high(), cL = cs.get(i).low();
            for (int j = i - lb; j <= i + lb && (isH || isL); j++) {
                if (j == i) continue;
                if (cs.get(j).high() >= cH) isH = false;
                if (cs.get(j).low()  <= cL) isL = false;
            }
            if (isH) liqs.add(new LiqLevel(i, "high", cH));
            if (isL) liqs.add(new LiqLevel(i, "low",  cL));
        }
        return liqs;
    }

    public List<OB> detectBreakerBlocks(List<Candle> cs, List<OB> obs, double[] atr) {
        List<OB> breakers = new ArrayList<>();
        for (OB ob : obs) {
            for (int j = ob.idx() + 2; j < cs.size(); j++) {
                Candle c = cs.get(j);
                if ("bull".equals(ob.type()) && c.close() < ob.bot() && isInstitutional(cs, j, atr)) {
                    breakers.add(new OB(ob.idx(),"bear",ob.top(),ob.bot(),ob.eq(),
                                        ob.strong(),ob.bos(),ob.formSes(),ob.instForm(),true));
                    break;
                }
                if ("bear".equals(ob.type()) && c.close() > ob.top() && isInstitutional(cs, j, atr)) {
                    breakers.add(new OB(ob.idx(),"bull",ob.top(),ob.bot(),ob.eq(),
                                        ob.strong(),ob.bos(),ob.formSes(),ob.instForm(),true));
                    break;
                }
            }
        }
        return breakers;
    }

    // ── IDM: Inducement sweep before OB touch ────────────────────────────
    public IDMResult detectIDM(List<Candle> cs, OB ob, List<LiqLevel> liqs, int touchIdx) {
        if (ob == null) return new IDMResult(false, null);
        int s = ob.idx() + 1;
        if ("bull".equals(ob.type())) {
            for (LiqLevel l : liqs) {
                if (!"low".equals(l.type()) || l.idx() < s || l.idx() >= touchIdx) continue;
                if (l.price() <= ob.bot() || l.price() >= cs.get(touchIdx).close()) continue;
                for (int si = l.idx(); si < touchIdx; si++) {
                    Candle sc = cs.get(si);
                    if (sc.low() < l.price() && sc.close() > l.price())
                        return new IDMResult(true, si);
                }
            }
        } else {
            for (LiqLevel l : liqs) {
                if (!"high".equals(l.type()) || l.idx() < s || l.idx() >= touchIdx) continue;
                if (l.price() >= ob.top() || l.price() <= cs.get(touchIdx).close()) continue;
                for (int si = l.idx(); si < touchIdx; si++) {
                    Candle sc = cs.get(si);
                    if (sc.high() > l.price() && sc.close() < l.price())
                        return new IDMResult(true, si);
                }
            }
        }
        return new IDMResult(false, null);
    }

    // ── OTE: 62–79% Fibonacci retracement ───────────────────────────────
    public OTEResult detectOTE(List<Candle> cs, int touchIdx, boolean isBull) {
        int start = Math.max(0, touchIdx - 30);
        List<Candle> sl = cs.subList(start, touchIdx);
        if (sl.size() < 5) return new OTEResult(false, 0, 0, 0);
        double swH = sl.stream().mapToDouble(Candle::high).max().orElse(0);
        double swL = sl.stream().mapToDouble(Candle::low).min().orElse(0);
        double range = swH - swL;
        if (range == 0) return new OTEResult(false, 0, swH, swL);
        double cur = cs.get(touchIdx).close();
        double fib = isBull ? (swH - cur) / range : (cur - swL) / range;
        boolean inOTE = fib >= OTE_FIB_LOW && fib <= OTE_FIB_HIGH;
        double oH = isBull ? swH - range * OTE_FIB_LOW  : swL + range * OTE_FIB_HIGH;
        double oL = isBull ? swH - range * OTE_FIB_HIGH : swL + range * OTE_FIB_LOW;
        return new OTEResult(inOTE, round(fib, 3), round(oH, 6), round(oL, 6));
    }

    // ── Zone age decay ───────────────────────────────────────────────────
    public ZoneAge calcZoneAge(OB ob, int touchIdx) {
        int age = touchIdx - ob.idx();
        double sc;
        String label;
        if (age <= OB_GOLDEN_AGE) {
            sc = W_FRESH; label = "Fresh(" + age + "b)";
        } else if (age <= 33) {
            sc = W_FRESH * (1.0 - (double)(age - OB_GOLDEN_AGE) / (33 - OB_GOLDEN_AGE));
            label = "Active(" + age + "b)";
        } else if (age <= OB_MAX_AGE) {
            sc = W_DECAY * ((double)(age - 33) / (OB_MAX_AGE - 33));
            label = "Aging(" + age + "b)";
        } else {
            sc = W_DECAY; label = "Stale(" + age + "b)";
        }
        return new ZoneAge(round(sc, 2), age, label);
    }

    // ── Smart SL placement ───────────────────────────────────────────────
    public SmartSLResult calcSmartSL(List<LiqLevel> liqs, boolean isBull,
                                     double entry, double curATR, String pair) {
        double pipSz  = (pair.contains("JPY") || "XAU/USD".equals(pair) || "NAS100".equals(pair))
                        ? 0.01 : 0.0001;
        double buf    = SMART_BUF_PIPS * pipSz;
        double atrFB  = isBull ? entry - curATR * 1.5 : entry + curATR * 1.5;
        int    dp     = (pair.contains("JPY") || "XAU/USD".equals(pair) || "NAS100".equals(pair)) ? 2 : 4;

        if (isBull) {
            LiqLevel best = null;
            for (LiqLevel l : liqs)
                if ("low".equals(l.type()) && l.price() < entry && l.price() > entry - curATR * 6)
                    if (best == null || l.price() > best.price()) best = l;
            if (best != null) {
                double sl = round(best.price() - buf, dp);
                if (entry - sl <= curATR * 3) return new SmartSLResult(sl, "smart", best.price());
            }
        } else {
            LiqLevel best = null;
            for (LiqLevel l : liqs)
                if ("high".equals(l.type()) && l.price() > entry && l.price() < entry + curATR * 6)
                    if (best == null || l.price() < best.price()) best = l;
            if (best != null) {
                double sl = round(best.price() + buf, dp);
                if (sl - entry <= curATR * 3) return new SmartSLResult(sl, "smart", best.price());
            }
        }
        return new SmartSLResult(round(atrFB, dp), "atr", null);
    }

    // ── HTF external liquidity TP ────────────────────────────────────────
    public HTFLiqResult findHTFLiq(List<LiqLevel> liqs, int touchIdx,
                                   boolean isBull, double entry, double slDist) {
        double maxRange = slDist * 12;
        if (isBull) {
            LiqLevel best = null;
            for (LiqLevel l : liqs)
                if ("high".equals(l.type()) && l.idx() < touchIdx
                    && l.price() > entry + slDist * 1.2 && l.price() < entry + maxRange)
                    if (best == null || l.price() < best.price()) best = l;
            if (best != null) {
                double rr = round((best.price() - entry) / slDist, 2);
                return new HTFLiqResult(best.price(), rr, "HTF liq @ " + round(best.price(),4) + " (" + rr + "R)");
            }
        } else {
            LiqLevel best = null;
            for (LiqLevel l : liqs)
                if ("low".equals(l.type()) && l.idx() < touchIdx
                    && l.price() < entry - slDist * 1.2 && l.price() > entry - maxRange)
                    if (best == null || l.price() > best.price()) best = l;
            if (best != null) {
                double rr = round((entry - best.price()) / slDist, 2);
                return new HTFLiqResult(best.price(), rr, "HTF liq @ " + round(best.price(),4) + " (" + rr + "R)");
            }
        }
        return new HTFLiqResult(null, 2.5, "Fixed 2.5R (no HTF liq found)");
    }

    // ── Volume gate ──────────────────────────────────────────────────────
    public VolumeGate calcVolumeGate(List<Candle> cs) {
        if (cs.size() < VOL_MA_PERIOD + 1) return new VolumeGate(false, 1.0);
        List<Candle> window = cs.subList(cs.size() - VOL_MA_PERIOD - 1, cs.size());
        double cur = Math.abs(window.get(window.size()-1).close() - window.get(window.size()-1).open());
        double ma  = window.subList(0, window.size()-1).stream()
                          .mapToDouble(c -> Math.abs(c.close() - c.open())).average().orElse(1);
        double ratio = ma > 0 ? round(cur / ma, 3) : 1.0;
        return new VolumeGate(ratio < 1.0, ratio);
    }

    // ── Liquidity sweep ──────────────────────────────────────────────────
    public boolean hasSweep(List<Candle> cs, int i, boolean isBull,
                            List<LiqLevel> liqs, double[] atr) {
        double ca = atr[i] > 0 ? atr[i] : 0.0001;
        for (int si = i; si >= Math.max(0, i-6); si--) {
            Candle sc = cs.get(si);
            if (isBull) {
                for (LiqLevel l : liqs)
                    if ("low".equals(l.type()) && l.idx() < si && l.idx() >= si-20
                        && sc.low() < l.price() - ca*0.2 && sc.close() > l.price()) return true;
            } else {
                for (LiqLevel l : liqs)
                    if ("high".equals(l.type()) && l.idx() < si && l.idx() >= si-20
                        && sc.high() > l.price() + ca*0.2 && sc.close() < l.price()) return true;
            }
        }
        return false;
    }

    // ── CHoCH ────────────────────────────────────────────────────────────
    public boolean hasCHoCH(List<Candle> cs, int from, boolean isBull) {
        for (int j = from+1; j < Math.min(from+8, cs.size()-1); j++) {
            Candle c = cs.get(j), p = cs.get(j-1);
            double body = Math.abs(c.close() - c.open());
            double lw   = Math.min(c.open(), c.close()) - c.low();
            double uw   = c.high() - Math.max(c.open(), c.close());
            if (isBull  && (c.close() > c.open() && c.close() > p.high() || lw > body*1.8)) return true;
            if (!isBull && (c.close() < c.open() && c.close() < p.low()  || uw > body*1.8)) return true;
        }
        return false;
    }

    // ── Candle close confirmation ─────────────────────────────────────────
    public boolean hasCandleClose(List<Candle> cs, int i, OB ob) {
        if (ob == null) return true;
        Candle c = cs.get(i);
        return c.close() >= ob.bot() && c.close() <= ob.top();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SCORING ENGINE (mirrors scoreSetup in JSX exactly)
    // ─────────────────────────────────────────────────────────────────────
    public ScoreResult score(
        boolean isBull, String ltfDir, String htfDir,
        boolean hasOB, boolean obInstF, boolean obBos, boolean obStrong,
        boolean hasFVG, boolean sweep, boolean choch,
        String session, boolean nearNews, boolean strongMom,
        String pd, String regime, double curATR, double atrThresh,
        boolean idmOk, boolean inOTE, double ageScore,
        boolean isBreaker, boolean candleClose, boolean volQuiet,
        double obBonus
    ) {
        String dir = isBull ? "bull" : "bear";

        double bos   = dir.equals(ltfDir) ? W_BOS : "neutral".equals(ltfDir) ? W_BOS*0.3 : 0;
        double chochS= choch ? W_CHOCH : 0;
        double fvgS  = hasFVG ? W_FVG  : 0;
        double obS   = hasOB  ? W_OB   : 0;
        double mtfS  = dir.equals(htfDir) ? W_MTF : "neutral".equals(htfDir) ? W_MTF_WEAK : 0;
        double swpS  = sweep ? W_SWEEP  : 0;
        double sesS  = ("London".equals(session)||"NewYork".equals(session)) ? W_SESSION
                     : "Asia".equals(session) ? W_SESSION*0.5 : 0;
        double newsS = nearNews ? -1 : W_NEWS;
        double momS  = strongMom ? W_MOMENTUM : 0;
        double idmS  = hasOB ? (idmOk ? W_IDM_OK : W_IDM_MISS) : 0;
        double brkS  = isBreaker ? W_BREAKER : 0;
        double oteS  = inOTE ? W_OTE : 0;
        double obSesS= hasOB && obInstF ? W_OB_SES : 0;
        double closeS= (hasOB && !candleClose) ? -0.8 : 0;
        double volS  = volQuiet ? W_VOL_QUIET : 0;
        double pdS   = (isBull && "discount".equals(pd)) || (!isBull && "premium".equals(pd)) ? W_PD : 0;

        // OB quality bonus
        double bonusCalc = obBonus;
        if (hasOB) { if (obBos) bonusCalc += 0.5; if (obStrong) bonusCalc += 0.5; }

        Map<String, Double> bd = new LinkedHashMap<>();
        bd.put("bos",      round(bos, 2));
        bd.put("choch",    round(chochS, 2));
        bd.put("fvg",      round(fvgS, 2));
        bd.put("ob",       round(obS, 2));
        bd.put("mtf",      round(mtfS, 2));
        bd.put("liqSweep", round(swpS, 2));
        bd.put("session",  round(sesS, 2));
        bd.put("news",     round(newsS, 2));
        bd.put("momentum", round(momS, 2));
        bd.put("idm",      round(idmS, 2));
        bd.put("breaker",  round(brkS, 2));
        bd.put("ote",      round(oteS, 2));
        bd.put("zoneAge",  round(ageScore, 2));
        bd.put("obSes",    round(obSesS, 2));
        bd.put("closeConf",round(closeS, 2));
        bd.put("pd",       round(pdS, 2));
        bd.put("volume",   round(volS, 2));

        double raw = bd.values().stream().mapToDouble(Double::doubleValue).sum() + bonusCalc;
        if ("ranging".equals(regime))  raw -= 0.8;
        if ("volatile".equals(regime)) raw -= 0.5;
        if (atrThresh > 0 && curATR < atrThresh) raw -= 1.0;

        double total = Math.min(Math.max(raw, 0), MAX_SCORE);
        total = round(total, 2);
        return new ScoreResult(total, bd, bonusCalc, regime, gradeOf(total));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────
    private String gradeOf(double s) {
        return s >= 16 ? "A+" : s >= 12 ? "A" : s >= 8 ? "B" : s >= 6 ? "C" : "D";
    }

    private double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}
