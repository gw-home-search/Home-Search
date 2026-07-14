package com.home.batch.metadata;

import com.home.application.ingest.metadata.OdcMetadataGapFillService;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

class OdcMetadataGapFillTasklet implements Tasklet {
    private final OdcMetadataGapFillService service;
    private final BuildingMetadataExecutionLock executionLock;
    private final int dailyRequestQuota;

    OdcMetadataGapFillTasklet(
            OdcMetadataGapFillService service, BuildingMetadataExecutionLock executionLock, int dailyRequestQuota) {
        this.service = service;
        this.executionLock = executionLock;
        this.dailyRequestQuota = dailyRequestQuota;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        int maxTargets = Integer.parseInt(params.get("maxTargets").toString());
        int approvedRequests = (int) Math.floor(dailyRequestQuota * 0.9d);
        if (dailyRequestQuota <= 0 || (long) maxTargets * 2L > approvedRequests) {
            throw new IllegalArgumentException("maxTargets x 2 must be at most 90% of the approved ODC daily quota");
        }
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            service.fill(
                    maxTargets,
                    optionalLong(params.get("fromComplexId")),
                    Long.parseLong(params.get("toComplexId").toString()),
                    UUID.fromString(params.get("requestId").toString()));
        }
        return RepeatStatus.FINISHED;
    }

    private Long optionalLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
