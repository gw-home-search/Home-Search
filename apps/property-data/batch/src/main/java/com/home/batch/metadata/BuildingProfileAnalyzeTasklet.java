package com.home.batch.metadata;

import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisService;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class BuildingProfileAnalyzeTasklet implements Tasklet {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuildingProfileAnalyzeTasklet.class);
    private final BuildingProfileAnalysisService service;
    private final BuildingMetadataExecutionLock executionLock;

    BuildingProfileAnalyzeTasklet(BuildingProfileAnalysisService service, BuildingMetadataExecutionLock executionLock) {
        this.service = service;
        this.executionLock = executionLock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        BuildingProfileAnalysisCommand command = new BuildingProfileAnalysisCommand(
                UUID.fromString(required(params, "collectionId")),
                UUID.fromString(required(params, "parseRunId")),
                UUID.fromString(required(params, "analysisRunId")),
                required(params, "rulesVersion"),
                Path.of(required(params, "outputDirectory")));
        try (BuildingMetadataExecutionLock.Lock ignored = executionLock.acquire()) {
            var summary = service.analyze(command);
            log.info(
                    "building profile analysis completed assignments={} matches={} comparisons={} fields={} alreadyCompleted={}",
                    summary.assignmentCount(),
                    summary.complexMatchCount(),
                    summary.comparisonCount(),
                    summary.fieldCount(),
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
