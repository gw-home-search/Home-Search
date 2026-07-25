package com.home.batch.event;

import com.home.application.event.PropertyEventOutboxRelayService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class PropertyEventRelayTasklet implements Tasklet {

    private final PropertyEventOutboxRelayService relayService;
    private final int batchSize;
    private final int maxBatches;
    private final Clock clock;

    PropertyEventRelayTasklet(
            PropertyEventOutboxRelayService relayService, int batchSize, int maxBatches, Clock clock) {
        this.relayService = Objects.requireNonNull(relayService);
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        if (maxBatches < 1) {
            throw new IllegalArgumentException("maxBatches must be positive");
        }
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Instant now = clock.instant();
        for (int batch = 0; batch < maxBatches; batch++) {
            var result = relayService.relayBatch(batchSize, now);
            if (result.failedCount() > 0) {
                throw new IllegalStateException(
                        "property event relay left " + result.failedCount() + " event(s) for retry");
            }
            if (result.selectedCount() < batchSize) {
                return RepeatStatus.FINISHED;
            }
        }
        throw new IllegalStateException("property event relay reached maxBatches before draining due events");
    }
}
