package com.home.batch.rtms;

import com.home.application.map.MapMarkerProjectionRefreshService;
import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class RtmsMapMarkerProjectionTasklet implements Tasklet {

    private final MapMarkerProjectionRefreshService refreshService;

    public RtmsMapMarkerProjectionTasklet(MapMarkerProjectionRefreshService refreshService) {
        this.refreshService = Objects.requireNonNull(refreshService);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        try {
            refreshService.refreshCurrent();
        } catch (RuntimeException exception) {
            chunkContext
                    .getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .put(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY, true);
            throw exception;
        }
        return RepeatStatus.FINISHED;
    }
}
