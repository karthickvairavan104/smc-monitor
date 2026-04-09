package com.smcmonitor.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "signals")
public class Signal {

    @Id private String id;

    @Indexed private String userId;
    private String pair;
    private boolean isBull;

    private double entry, sl, tp1, tp2, rr;

    private double score;
    private String grade;
    private Map<String, Double> breakdown;
    private String regime;

    // v14 signals
    private boolean idmConfirmed, inOTE, isBreaker, hasOB, hasFVG;
    private boolean sweep, choch, conf, strongMom, eqHit, candleClose;
    private double  oteFibPct;
    private String  zoneAgeLabel, htfLiqLabel;

    // v15 signals
    private String  slMethod;
    private Double  slLiqLevel;
    private boolean volumeQuiet;
    private double  bodyRatio;

    private String ltfDir, htfDir, pdZone, session;
    private double curATR;

    @Builder.Default private String status = "LIVE";
    private String  outcome;
    private Double  pnl;
    private String  pairCat;

    @CreatedDate @Indexed private Instant createdAt;
}
