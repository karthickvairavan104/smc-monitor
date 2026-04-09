package com.smcmonitor.controller;

import com.smcmonitor.model.Trade;
import com.smcmonitor.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalController {

    private final TradeRepository tradeRepo;

    @GetMapping
    public List<Trade> get(Authentication auth) {
        return tradeRepo.findByUserIdOrderByCreatedAtDesc(uid(auth));
    }

    @PostMapping
    public Trade log(@RequestBody Trade trade, Authentication auth) {
        trade.setUserId(uid(auth));
        return tradeRepo.save(trade);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
        @PathVariable String id,
        @RequestBody Map<String, Object> updates,
        Authentication auth
    ) {
        return tradeRepo.findById(id)
            .filter(t -> t.getUserId().equals(uid(auth)))
            .map(t -> {
                if (updates.containsKey("outcome")) t.setOutcome((String) updates.get("outcome"));
                if (updates.containsKey("pnl"))     t.setPnl(((Number) updates.get("pnl")).doubleValue());
                return ResponseEntity.ok(tradeRepo.save(t));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id, Authentication auth) {
        return tradeRepo.findById(id)
            .filter(t -> t.getUserId().equals(uid(auth)))
            .map(t -> { tradeRepo.delete(t); return ResponseEntity.<Void>ok().build(); })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> clearAll(Authentication auth) {
        tradeRepo.deleteAll(tradeRepo.findByUserIdOrderByCreatedAtDesc(uid(auth)));
        return ResponseEntity.ok().build();
    }

    private String uid(Authentication a) { return (String) a.getPrincipal(); }
}
