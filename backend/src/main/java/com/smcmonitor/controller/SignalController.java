package com.smcmonitor.controller;

import com.smcmonitor.model.Signal;
import com.smcmonitor.model.Trade;
import com.smcmonitor.model.User;
import com.smcmonitor.repository.SignalRepository;
import com.smcmonitor.repository.UserRepository;
import com.smcmonitor.service.AutoCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalRepository signalRepo;
    private final UserRepository   userRepo;
    private final AutoCloseService autoClose;

    /** GET /api/signals  — all LIVE signals for the authenticated user */
    @GetMapping
    public List<Signal> getLive(Authentication auth) {
        return signalRepo.findByUserIdAndStatusOrderByCreatedAtDesc(uid(auth), "LIVE");
    }

    /** GET /api/signals/all  — LIVE + CLOSED */
    @GetMapping("/all")
    public List<Signal> getAll(Authentication auth) {
        return signalRepo.findByUserIdOrderByCreatedAtDesc(uid(auth));
    }

    /** PATCH /api/signals/{id}/close  — manual override close */
    @PatchMapping("/{id}/close")
    public ResponseEntity<?> manualClose(
        @PathVariable String id,
        @RequestBody Map<String, String> body,
        Authentication auth
    ) {
        return signalRepo.findById(id)
            .filter(s -> s.getUserId().equals(uid(auth)) && "LIVE".equals(s.getStatus()))
            .flatMap(sig -> userRepo.findById(uid(auth)).map(user -> {
                String outcome = body.getOrDefault("outcome", "manual");
                Trade trade = autoClose.manualClose(sig, outcome, user);
                return ResponseEntity.ok(Map.of("signal", sig, "trade", trade != null ? trade : Map.of()));
            }))
            .orElse(ResponseEntity.notFound().build());
    }

    private String uid(Authentication a) { return (String) a.getPrincipal(); }
}
