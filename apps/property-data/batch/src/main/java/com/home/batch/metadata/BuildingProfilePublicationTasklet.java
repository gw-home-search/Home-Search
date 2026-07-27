package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfilePublicationCommand;
import com.home.application.ingest.buildingprofile.BuildingProfilePublicationService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class BuildingProfilePublicationTasklet implements Tasklet {
    private static final Logger log = LoggerFactory.getLogger(BuildingProfilePublicationTasklet.class);
    private final BuildingProfilePublicationService service;
    private final BuildingMetadataExecutionLock executionLock;

    BuildingProfilePublicationTasklet(
            BuildingProfilePublicationService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        var command = new BuildingProfilePublicationCommand(
                UUID.fromString(required(params, "publicationId")),
                UUID.fromString(required(params, "projectionRunId")),
                required(params, "rulesVersion"),
                requiredBoolean(params, "publish"),
                requiredBoolean(params, "backfill"));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            var summary = service.publish(command);
            log.info(
                    "building profile publication completed status={} sites={} buildings={} hierarchy={} evidence={} summaries={} digest={} alreadyCompleted={}",
                    summary.status(),
                    summary.siteCount(),
                    summary.buildingCount(),
                    summary.hierarchyCount(),
                    summary.evidenceCount(),
                    summary.summaryCount(),
                    summary.contentSha256(),
                    summary.alreadyCompleted());
        }
        return RepeatStatus.FINISHED;
    }

    private String required(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.toString();
    }

    private boolean requiredBoolean(Map<String, Object> params, String name) {
        String value = required(params, name);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
