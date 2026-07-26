package com.home.application.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PropertyEventOutboxRetentionService {

    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 10_000;

    private final PropertyEventOutboxRepository repository;

    public PropertyEventOutboxRetentionService(PropertyEventOutboxRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public int deleteExpired(Instant now, Duration retention, int batchSize, int maxBatches) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(retention);
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (maxBatches < 1 || maxBatches > MAX_BATCHES) {
            throw new IllegalArgumentException("maxBatches must be between 1 and " + MAX_BATCHES);
        }

        Instant cutoff = now.minus(retention);
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = repository.deletePublishedBefore(cutoff, batchSize);
            if (deleted < 0 || deleted > batchSize) {
                throw new IllegalStateException("repository returned an invalid deleted row count: " + deleted);
            }
            total = Math.addExact(total, deleted);
            if (deleted < batchSize) {
                return total;
            }
        }
        throw new IllegalStateException("property event outbox retention reached maxBatches before draining");
    }
}
