package com.home.application.insight.port;

import java.time.Instant;

public interface InsightRetentionRepository {
    RetentionResult deleteExpired(Instant cutoff);

    record RetentionResult(int inboxDeleted, int consumerEvidenceDeleted) {
        public RetentionResult {
            if (inboxDeleted < 0 || consumerEvidenceDeleted < 0) {
                throw new IllegalArgumentException("deleted counts must be non-negative");
            }
        }
    }
}
