package com.home.batch.metadata;

import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingMetadataCollectTasklet implements Tasklet {
    private final BuildingMetadataBatchService service;
    private final BuildingMetadataExecutionLock executionLock;
    private final int dailyRequestQuota;

    BuildingMetadataCollectTasklet(
            BuildingMetadataBatchService service, BuildingMetadataExecutionLock executionLock, int dailyRequestQuota) {
        this.service = service;
        this.executionLock = executionLock;
        this.dailyRequestQuota = dailyRequestQuota;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        int maxRequests = Integer.parseInt(params.get("maxRequests").toString());
        int approvedLimit = (int) Math.floor(dailyRequestQuota * 0.9d);
        if (dailyRequestQuota <= 0 || maxRequests > approvedLimit)
            throw new IllegalArgumentException("maxRequests must be at most 90% of the approved daily quota");
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            service.collect(
                    params.get("mode").toString(),
                    maxRequests,
                    optionalLong(params.get("fromComplexId")),
                    optionalLong(params.get("toComplexId")),
                    UUID.fromString(params.get("requestId").toString()));
        }
        return RepeatStatus.FINISHED;
    }

    private Long optionalLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
