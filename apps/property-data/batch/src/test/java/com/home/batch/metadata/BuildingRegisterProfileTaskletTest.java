package com.home.batch.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisService;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisSummary;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectSummary;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectionService;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionService;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionSummary;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayService;
import com.home.application.ingest.buildingprofile.BuildingProfileReplaySummary;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportCommand;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportService;
import com.home.application.ingest.buildingregister.BuildingRegisterDailyRequestUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class BuildingRegisterProfileTaskletTest {
    @Test
    void profileCollectRequiresFixedSurfaceAndDefaultsToParallelismTwo() throws Exception {
        BuildingProfileCollectionService service = mock(BuildingProfileCollectionService.class);
        BuildingMetadataExecutionLock lock = lock();
        BuildingRegisterDailyRequestUsage usage = mock(BuildingRegisterDailyRequestUsage.class);
        given(service.collect(org.mockito.ArgumentMatchers.any(BuildingProfileCollectCommand.class)))
                .willReturn(new BuildingProfileCollectSummary(1500, 10, 2, 0, false));
        BuildingProfileCollectTasklet tasklet = new BuildingProfileCollectTasklet(service, lock, usage, 10_000);
        Map<String, Object> parameters = collectParameters();

        tasklet.execute(null, context(parameters));

        ArgumentCaptor<BuildingProfileCollectCommand> command =
                ArgumentCaptor.forClass(BuildingProfileCollectCommand.class);
        verify(service).collect(command.capture());
        assertThat(command.getValue().sampleSize()).isEqualTo(1500);
        assertThat(command.getValue().targetScope())
                .isEqualTo(com.home.domain.complex.buildingprofile.BuildingProfileTargetScope.VALIDATION_SAMPLE);
        assertThat(command.getValue().parallelism()).isEqualTo(2);

        Map<String, Object> invalid = new java.util.HashMap<>(parameters);
        invalid.put("strategy", "adaptive");
        assertThatThrownBy(() -> tasklet.execute(null, context(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy");
    }

    @Test
    void replayAndAnalyzeUseOfflineParametersAndSharedLock() throws Exception {
        BuildingMetadataExecutionLock lock = lock();
        BuildingProfileReplayService replay = mock(BuildingProfileReplayService.class);
        given(replay.replay(org.mockito.ArgumentMatchers.any(BuildingProfileReplayCommand.class)))
                .willReturn(new BuildingProfileReplaySummary(10, 20, 0, true));
        new BuildingProfileReplayTasklet(replay, lock)
                .execute(
                        null,
                        context(Map.of(
                                "sourceCollectionId", "123e4567-e89b-12d3-a456-426614174210",
                                "parseRunId", "123e4567-e89b-12d3-a456-426614174211",
                                "parserVersion", "PROFILE_V1",
                                "maxPages", "100")));
        verify(replay).replay(org.mockito.ArgumentMatchers.any(BuildingProfileReplayCommand.class));

        BuildingProfileAnalysisService analyze = mock(BuildingProfileAnalysisService.class);
        given(analyze.analyze(org.mockito.ArgumentMatchers.any(BuildingProfileAnalysisCommand.class)))
                .willReturn(new BuildingProfileAnalysisSummary(1, 1, 1, 1, List.of(), false));
        new BuildingProfileAnalyzeTasklet(analyze, lock)
                .execute(
                        null,
                        context(Map.of(
                                "collectionId", "123e4567-e89b-12d3-a456-426614174210",
                                "parseRunId", "123e4567-e89b-12d3-a456-426614174211",
                                "analysisRunId", "123e4567-e89b-12d3-a456-426614174212",
                                "rulesVersion", "PROFILE_V1",
                                "outputDirectory", "/tmp/home-search-profile-test")));
        verify(analyze).analyze(org.mockito.ArgumentMatchers.any(BuildingProfileAnalysisCommand.class));

        BuildingProfileProjectionService project = mock(BuildingProfileProjectionService.class);
        given(project.project(org.mockito.ArgumentMatchers.any(BuildingProfileProjectionCommand.class)))
                .willReturn(new BuildingProfileProjectionSummary(55, 44_200, 38_310, 260_197, "a".repeat(64), false));
        new BuildingProfileProjectTasklet(project, lock)
                .execute(
                        null,
                        context(Map.of(
                                "projectionRunId", "123e4567-e89b-12d3-a456-426614174215",
                                "analysisRunId", "123e4567-e89b-12d3-a456-426614174212",
                                "projectionVersion", "PROFILE_PROJECTION_V1")));
        verify(project).project(org.mockito.ArgumentMatchers.any(BuildingProfileProjectionCommand.class));
    }

    @Test
    void nationwideCollectDerivesPopulationAndUsesRequestedParallelism() throws Exception {
        BuildingProfileCollectionService service = mock(BuildingProfileCollectionService.class);
        BuildingRegisterDailyRequestUsage usage = mock(BuildingRegisterDailyRequestUsage.class);
        given(service.collect(org.mockito.ArgumentMatchers.any(BuildingProfileCollectCommand.class)))
                .willReturn(new BuildingProfileCollectSummary(43_721, 10, 2, 0, false));
        BuildingProfileCollectTasklet tasklet = new BuildingProfileCollectTasklet(service, lock(), usage, 400_000);
        Map<String, Object> parameters = new java.util.HashMap<>(collectParameters());
        parameters.put("targetScope", "nationwide-staging");
        parameters.remove("sampleSize");
        parameters.put("maxRequests", "300000");
        parameters.put("parallelism", "3");

        tasklet.execute(null, context(parameters));

        ArgumentCaptor<BuildingProfileCollectCommand> command =
                ArgumentCaptor.forClass(BuildingProfileCollectCommand.class);
        verify(service).collect(command.capture());
        assertThat(command.getValue().targetScope())
                .isEqualTo(com.home.domain.complex.buildingprofile.BuildingProfileTargetScope.NATIONWIDE_STAGING);
        assertThat(command.getValue().sampleSize()).isNull();
        assertThat(command.getValue().parallelism()).isEqualTo(3);
    }

    @Test
    void legalDongImportAcceptsOnlyApprovedAbsoluteCsv(@TempDir Path temp) throws Exception {
        LegalDongCodeImportService service = mock(LegalDongCodeImportService.class);
        Path source = temp.resolve("legal-dong.csv");
        Files.writeString(
                source, "old_legal_dong_code,new_legal_dong_code,effective_date\n2811010100,2811010200,2026-07-01\n");
        LegalDongCodeImportTasklet tasklet = new LegalDongCodeImportTasklet(service, lock());

        tasklet.execute(
                null,
                context(Map.of(
                        "sourceFile", source.toString(),
                        "effectiveDate", "2026-07-01",
                        "importId", "123e4567-e89b-12d3-a456-426614174214")));

        ArgumentCaptor<LegalDongCodeImportCommand> command = ArgumentCaptor.forClass(LegalDongCodeImportCommand.class);
        verify(service).importMappings(command.capture());
        assertThat(command.getValue().mappings()).hasSize(1);
        assertThat(command.getValue().sourceSha256()).hasSize(64);

        Files.writeString(source, "unapproved\n");
        assertThatThrownBy(() -> tasklet.execute(
                        null,
                        context(Map.of(
                                "sourceFile", source.toString(),
                                "effectiveDate", "2026-07-01",
                                "importId", "123e4567-e89b-12d3-a456-426614174214"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
    }

    private BuildingMetadataExecutionLock lock() {
        BuildingMetadataExecutionLock lock = mock(BuildingMetadataExecutionLock.class);
        given(lock.acquire()).willReturn(mock(BuildingMetadataExecutionLock.Lock.class));
        return lock;
    }

    private Map<String, Object> collectParameters() {
        return Map.ofEntries(
                Map.entry("collectionId", "123e4567-e89b-12d3-a456-426614174210"),
                Map.entry("requestId", "123e4567-e89b-12d3-a456-426614174213"),
                Map.entry("runDate", "2026-07-21"),
                Map.entry("purpose", "profile-discovery"),
                Map.entry("targetScope", "validation-sample"),
                Map.entry("strategy", "compare-recap-title"),
                Map.entry("sampleSize", "1500"),
                Map.entry("selectionSeed", "profile-v1"),
                Map.entry("maxRequests", "100"));
    }

    private ChunkContext context(Map<String, Object> params) {
        ChunkContext context = mock(ChunkContext.class);
        StepContext step = mock(StepContext.class);
        given(context.getStepContext()).willReturn(step);
        given(step.getJobParameters()).willReturn(params);
        return context;
    }
}
