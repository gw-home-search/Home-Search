package com.home.batch.event;

import com.home.application.event.PropertyEventOutboxRetentionService;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class PropertyEventOutboxRetentionTasklet implements Tasklet {

    private final PropertyEventOutboxRetentionService service;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatches;
    private final Clock clock;

    PropertyEventOutboxRetentionTasklet(
            PropertyEventOutboxRetentionService service,
            Duration retention,
            int batchSize,
            int maxBatches,
            Clock clock) {
        this.service = Objects.requireNonNull(service);
        this.retention = Objects.requireNonNull(retention);
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        service.deleteExpired(clock.instant(), retention, batchSize, maxBatches);
        return RepeatStatus.FINISHED;
    }
}
