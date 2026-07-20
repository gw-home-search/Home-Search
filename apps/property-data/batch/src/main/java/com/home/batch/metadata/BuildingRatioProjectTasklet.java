package com.home.batch.metadata;

import com.home.application.ingest.buildingregister.BuildingRatioProjectCommand;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionService;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingRatioProjectTasklet implements Tasklet {
    private final BuildingRatioProjectionService service;
    private final BuildingMetadataExecutionLock executionLock;

    BuildingRatioProjectTasklet(BuildingRatioProjectionService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        var command = new BuildingRatioProjectCommand(
                UUID.fromString(params.get("collectionId").toString()),
                UUID.fromString(params.get("requestId").toString()),
                Integer.parseInt(params.get("maxTargets").toString()),
                optionalLong(params.get("fromComplexId")),
                optionalLong(params.get("toComplexId")));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            service.project(command);
        }
        return RepeatStatus.FINISHED;
    }

    private Long optionalLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
