package com.smcmonitor.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "trades")
public class Trade {

    @Id private String id;

    @Indexed private String userId;
    private String  pair;
    private boolean isBull;
    private double  entry, sl, tp1, tp2;
    private double  score;
    private String  grade, regime, session;
    private boolean idmConfirmed, inOTE, isBreaker;
    private String  slMethod;

    private String  outcome;
    private double  pnl;
    private double  kellyPct;
    private boolean autoClose;
    private String  closeReason;
    private double  balanceAfter;

    @CreatedDate @Indexed private Instant createdAt;
    private Instant closedAt;
}
