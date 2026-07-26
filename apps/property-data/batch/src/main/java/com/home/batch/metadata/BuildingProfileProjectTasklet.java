package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfileProjectionCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionService;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingProfileProjectTasklet implements Tasklet {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuildingProfileProjectTasklet.class);
    private final BuildingProfileProjectionService service;
    private final BuildingMetadataExecutionLock executionLock;

    BuildingProfileProjectTasklet(
            BuildingProfileProjectionService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        var command = new BuildingProfileProjectionCommand(
                UUID.fromString(required(params, "projectionRunId")),
                UUID.fromString(required(params, "analysisRunId")),
                required(params, "projectionVersion"));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            var summary = service.project(command);
            log.info(
                    "building profile projection completed fields={} complexes={} projectable={} buildings={} alreadyCompleted={}",
                    summary.eligibleFieldCount(),
                    summary.complexCount(),
                    summary.projectableComplexCount(),
                    summary.buildingCount(),
                    summary.alreadyCompleted());
        }
        return RepeatStatus.FINISHED;
    }

    private String required(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.toString();
    }
}
