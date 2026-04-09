package com.smcmonitor.repository;

import com.smcmonitor.model.Signal;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.Instant;
import java.util.List;

public interface SignalRepository extends MongoRepository<Signal, String> {
    List<Signal> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);
    List<Signal> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Signal> findByStatusAndCreatedAtAfter(String status, Instant after);
    void deleteByUserIdAndCreatedAtBefore(String userId, Instant before);
}
