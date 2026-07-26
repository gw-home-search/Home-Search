package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfileRepairCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileRepairService;
import com.home.application.ingest.buildingregister.BuildingRegisterDailyRequestUsage;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class BuildingProfileRepairTasklet implements Tasklet {
    private static final Logger log = LoggerFactory.getLogger(BuildingProfileRepairTasklet.class);

    private final BuildingProfileRepairService service;
    private final BuildingMetadataExecutionLock executionLock;
    private final BuildingRegisterDailyRequestUsage requestUsage;
    private final int dailyRequestQuota;

    BuildingProfileRepairTasklet(
            BuildingProfileRepairService service,
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
        String policy = required(params, "repairPolicyVersion");
        if (!"PROFILE_REPAIR_V1".equals(policy)) {
            throw new IllegalArgumentException("repairPolicyVersion must be PROFILE_REPAIR_V1");
        }
        int maxRequests = Integer.parseInt(required(params, "maxRequests"));
        int approvedLimit = (int) Math.floor(dailyRequestQuota * 0.9d);
        if (dailyRequestQuota <= 0 || maxRequests <= 0 || maxRequests > 20_000 || maxRequests > approvedLimit) {
            throw new IllegalArgumentException("maxRequests exceeds repair or approved daily limit");
        }
        LocalDate runDate = LocalDate.parse(required(params, "runDate"));
        BuildingProfileRepairCommand command = new BuildingProfileRepairCommand(
                UUID.fromString(required(params, "sourceCollectionId")),
                UUID.fromString(required(params, "collectionId")),
                UUID.fromString(required(params, "requestId")),
                runDate,
                policy,
                maxRequests,
                Integer.parseInt(required(params, "parallelism")));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            int used = requestUsage.usedRequests(runDate);
            if (used < 0 || maxRequests > approvedLimit - used) {
                throw new IllegalArgumentException("maxRequests exceeds remaining daily request budget");
            }
            var summary = service.repair(command);
            log.info(
                    "building profile repair completed targets={} requests={} completed={} failures={} campaignCompleted={}",
                    summary.targetCount(),
                    summary.requestCount(),
                    summary.completedCount(),
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
