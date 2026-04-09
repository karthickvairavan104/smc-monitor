package com.smcmonitor.repository;

import com.smcmonitor.model.Trade;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface TradeRepository extends MongoRepository<Trade, String> {
    List<Trade> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Trade> findByUserIdAndOutcomeOrderByCreatedAtDesc(String userId, String outcome);
    long countByUserIdAndOutcome(String userId, String outcome);
}
