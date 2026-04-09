package com.smcmonitor.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id private String id;

    @Indexed(unique = true) private String email;
    private String name, picture, googleId;

    @Builder.Default private double balance     = 10_000.0;
    @Builder.Default private double peakBalance = 10_000.0;
    @Builder.Default private double baseRisk    = 1.0;

    private List<Double> equityCurve;

    @Builder.Default private UserSettings settings = new UserSettings();

    @CreatedDate  private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UserSettings {
        private boolean soundOn      = true;
        private boolean notifOn      = false;
        private boolean autoClose    = true;
        private int     scanInterval = 60;
        private int     alertScore   = 6;
        private int     maxSignals   = 20;
        private List<String> watchPairs = List.of(
            "EUR/USD","GBP/USD","USD/JPY","USD/CHF","AUD/USD","USD/CAD","NZD/USD",
            "EUR/GBP","EUR/JPY","GBP/JPY","EUR/AUD","AUD/JPY","CAD/JPY",
            "XAU/USD","NAS100"
        );
    }
}
