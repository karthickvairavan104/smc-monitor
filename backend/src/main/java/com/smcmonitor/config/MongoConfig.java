package com.smcmonitor.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MongoConfig {

    private final MongoTemplate mongo;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try {
            // signals: userId+status composite (most common query)
            mongo.indexOps("signals")
                .ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                                        .on("status", Sort.Direction.ASC)
                                        .on("createdAt", Sort.Direction.DESC));

            // signals: status+createdAt for auto-close sweeps
            mongo.indexOps("signals")
                .ensureIndex(new Index().on("status", Sort.Direction.ASC)
                                        .on("createdAt", Sort.Direction.ASC));

            // trades: userId + createdAt for journal
            mongo.indexOps("trades")
                .ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                                        .on("createdAt", Sort.Direction.DESC));

            // trades: userId + outcome for stats queries
            mongo.indexOps("trades")
                .ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                                        .on("outcome", Sort.Direction.ASC));

            // users: unique email
            mongo.indexOps("users")
                .ensureIndex(new Index().on("email", Sort.Direction.ASC).unique());

            // users: googleId
            mongo.indexOps("users")
                .ensureIndex(new Index().on("googleId", Sort.Direction.ASC));

            log.info("MongoDB indexes ensured");
        } catch (Exception e) {
            log.warn("Index setup warning: {}", e.getMessage());
        }
    }
}
