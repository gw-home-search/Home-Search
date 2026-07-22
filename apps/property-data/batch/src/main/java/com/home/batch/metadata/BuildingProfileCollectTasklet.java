package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfileCollectCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterDailyRequestUsage;
import com.home.domain.complex.buildingprofile.BuildingProfileTargetScope;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingProfileCollectTasklet implements Tasklet {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuildingProfileCollectTasklet.class);
    private final BuildingProfileCollectionService service;
    private final BuildingMetadataExecutionLock executionLock;
    private final BuildingRegisterDailyRequestUsage requestUsage;
    private final int dailyRequestQuota;

    BuildingProfileCollectTasklet(
            BuildingProfileCollectionService service,
            BuildingMetadataExecutionLock executionLock,
            BuildingRegisterDailyRequestUsage requestUsage,
            int dailyRequestQuota) {
        this.service = service;
        this.executionLock = executionLock;
        this.requestUsage = requestUsage;
        this.dailyRequestQuota = dailyRequestQuota;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        requireLiteral(params, "purpose", "profile-discovery");
        requireLiteral(params, "strategy", "compare-recap-title");
        BuildingProfileTargetScope targetScope = targetScope(required(params, "targetScope"));
        Integer sampleSize = params.get("sampleSize") == null
                ? null
                : Integer.parseInt(params.get("sampleSize").toString());
        int maxRequests = Integer.parseInt(required(params, "maxRequests"));
        int approvedLimit = (int) Math.floor(dailyRequestQuota * 0.9d);
        if (dailyRequestQuota <= 0 || maxRequests <= 0 || maxRequests > approvedLimit) {
            throw new IllegalArgumentException("maxRequests must be at most 90% of the approved daily quota");
        }
        LocalDate runDate = LocalDate.parse(required(params, "runDate"));
        BuildingProfileCollectCommand command = new BuildingProfileCollectCommand(
                UUID.fromString(required(params, "collectionId")),
                UUID.fromString(required(params, "requestId")),
                runDate,
                targetScope,
                sampleSize,
                required(params, "selectionSeed"),
                maxRequests,
                params.get("parallelism") == null
                        ? 2
                        : Integer.parseInt(params.get("parallelism").toString()));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            int used = requestUsage.usedRequests(runDate);
            if (used < 0 || maxRequests > approvedLimit - used) {
                throw new IllegalArgumentException("maxRequests exceeds remaining daily request budget");
            }
            var summary = service.collect(command);
            log.info(
                    "building profile collection completed pnus={} requests={} collected={} failures={} campaignCompleted={}",
                    summary.pnuCount(),
                    summary.requestCount(),
                    summary.collectedCount(),
                    summary.failureCount(),
                    summary.completed());
        }
        return RepeatStatus.FINISHED;
    }

    private BuildingProfileTargetScope targetScope(String value) {
        return switch (value) {
            case "validation-sample" -> BuildingProfileTargetScope.VALIDATION_SAMPLE;
            case "nationwide-staging" -> BuildingProfileTargetScope.NATIONWIDE_STAGING;
            default -> throw new IllegalArgumentException(
                    "targetScope must be validation-sample or nationwide-staging");
        };
    }

    private void requireLiteral(Map<String, Object> params, String name, String expected) {
        if (!expected.equals(required(params, name))) throw new IllegalArgumentException(name + " must be " + expected);
    }

    private String required(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.toString();
    }
}
