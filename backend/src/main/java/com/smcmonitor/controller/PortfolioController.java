package com.smcmonitor.controller;

import com.smcmonitor.model.User;
import com.smcmonitor.repository.TradeRepository;
import com.smcmonitor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final UserRepository  userRepo;
    private final TradeRepository tradeRepo;

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        return userRepo.findById(uid(auth))
            .map(u -> ResponseEntity.ok(Map.of(
                "balance",     u.getBalance(),
                "peakBalance", u.getPeakBalance(),
                "equityCurve", u.getEquityCurve() != null ? u.getEquityCurve() : List.of(10_000.0),
                "totalTrades", tradeRepo.findByUserIdOrderByCreatedAtDesc(uid(auth)).size(),
                "wins",        tradeRepo.countByUserIdAndOutcome(uid(auth), "win"),
                "losses",      tradeRepo.countByUserIdAndOutcome(uid(auth), "loss"),
                "partials",    tradeRepo.countByUserIdAndOutcome(uid(auth), "partial")
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(Authentication auth) {
        return userRepo.findById(uid(auth)).map(u -> {
            u.setBalance(10_000.0);
            u.setPeakBalance(10_000.0);
            u.setEquityCurve(List.of(10_000.0));
            return ResponseEntity.ok(userRepo.save(u));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/settings")
    public ResponseEntity<?> updateSettings(
        @RequestBody User.UserSettings settings, Authentication auth
    ) {
        return userRepo.findById(uid(auth)).map(u -> {
            u.setSettings(settings);
            return ResponseEntity.ok(userRepo.save(u));
        }).orElse(ResponseEntity.notFound().build());
    }

    private String uid(Authentication a) { return (String) a.getPrincipal(); }
}
