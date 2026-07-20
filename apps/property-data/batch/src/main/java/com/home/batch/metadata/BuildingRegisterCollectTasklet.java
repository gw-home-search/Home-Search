package com.home.batch.metadata;

import com.home.application.ingest.buildingregister.BuildingRegisterCampaignCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignService;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingRegisterCollectTasklet implements Tasklet {
    private final BuildingRegisterCampaignService service;
    private final BuildingMetadataExecutionLock executionLock;
    private final int dailyRequestQuota;

    BuildingRegisterCollectTasklet(
            BuildingRegisterCampaignService service,
            BuildingMetadataExecutionLock executionLock,
            int dailyRequestQuota) {
        this.service = service;
        this.executionLock = executionLock;
        this.dailyRequestQuota = dailyRequestQuota;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        int maxRequests = Integer.parseInt(params.get("maxRequests").toString());
        int approvedLimit = (int) Math.floor(dailyRequestQuota * 0.9d);
        if (dailyRequestQuota <= 0 || maxRequests > approvedLimit) {
            throw new IllegalArgumentException("maxRequests must be at most 90% of the approved daily quota");
        }
        var command = new BuildingRegisterCampaignCommand(
                UUID.fromString(params.get("collectionId").toString()),
                UUID.fromString(params.get("requestId").toString()),
                LocalDate.parse(params.get("runDate").toString()),
                BuildingRegisterCollectionMode.valueOf(
                        params.get("mode").toString().toUpperCase(Locale.ROOT)),
                strategy(params.get("strategy").toString()),
                maxRequests,
                optionalLong(params.get("fromComplexId")),
                Long.parseLong(params.get("toComplexId").toString()));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            service.collect(command);
        }
        return RepeatStatus.FINISHED;
    }

    private BuildingRegisterCollectionStrategy strategy(String value) {
        return switch (value) {
            case "adaptive" -> BuildingRegisterCollectionStrategy.ADAPTIVE;
            case "full-hierarchy" -> BuildingRegisterCollectionStrategy.FULL_HIERARCHY;
            default -> throw new IllegalArgumentException("unsupported building register strategy");
        };
    }

    private Long optionalLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
