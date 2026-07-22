package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfileReplayCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayService;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingProfileReplayTasklet implements Tasklet {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuildingProfileReplayTasklet.class);
    private final BuildingProfileReplayService service;
    private final BuildingMetadataExecutionLock executionLock;

    BuildingProfileReplayTasklet(BuildingProfileReplayService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        BuildingProfileReplayCommand command = new BuildingProfileReplayCommand(
                UUID.fromString(required(params, "sourceCollectionId")),
                UUID.fromString(required(params, "parseRunId")),
                required(params, "parserVersion"),
                Integer.parseInt(required(params, "maxPages")));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            var summary = service.replay(command);
            log.info(
                    "building profile replay completed pages={} records={} failures={} runCompleted={}",
                    summary.pageCount(),
                    summary.recordCount(),
                    summary.failureCount(),
                    summary.completed());
        }
        return RepeatStatus.FINISHED;
    }

    private String required(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.toString();
    }
}
