package com.home.infrastructure.persistence.user;

import com.home.application.insight.port.InsightRetentionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInsightRetentionRepository implements InsightRetentionRepository {

    private final JdbcClient jdbcClient;

    public JdbcInsightRetentionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public RetentionResult deleteExpired(Instant cutoff) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        int inboxDeleted = jdbcClient
                .sql("DELETE FROM users.insight_inbox WHERE expires_at <= :cutoff")
                .param("cutoff", timestamp)
                .update();
        int evidenceDeleted = jdbcClient
                .sql("DELETE FROM users.event_consumer_inbox WHERE expires_at <= :cutoff")
                .param("cutoff", timestamp)
                .update();
        return new RetentionResult(inboxDeleted, evidenceDeleted);
    }
}
