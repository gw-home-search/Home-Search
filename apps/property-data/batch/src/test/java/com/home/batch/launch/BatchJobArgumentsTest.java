package com.home.batch.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchJobArgumentsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T16:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("daily job은 requestId가 있으면 runDate 기본값과 canonical UUID를 identifying parameter로 채운다")
    void dailyJobDefaultsRunDateToKstToday() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "rtmsDailyRefreshJob", Map.of("requestId", "123e4567-e89b-12d3-a456-426614174000"), clock);

        assertThat(arguments.jobName()).isEqualTo("rtmsDailyRefreshJob");
        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
        assertThat(arguments.jobParameters().getParameter("runDate").identifying())
                .isTrue();
        assertThat(arguments.jobParameters().getString("requestId")).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
    }

    @Test
    @DisplayName("rolling insight job은 daily와 같은 runDate 및 requestId 식별 계약을 사용한다")
    void rollingInsightJobUsesOperationalRunDate() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "marketInsightRolling7dJob", Map.of("requestId", "123e4567-e89b-12d3-a456-426614174011"), clock);

        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
        assertThat(arguments.jobParameters().getString("requestId")).isEqualTo("123e4567-e89b-12d3-a456-426614174011");
    }

    @Test
    @DisplayName("property event relay job은 AWS scheduler execution id를 canonical UUID로 변환한다")
    void propertyEventRelayUsesSchedulerExecutionIdentity() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "propertyEventRelayJob", Map.of("schedulerExecutionId", "d32c5kddcf5bb8c3"), clock);

        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
        assertThat(arguments.jobParameters().getString("requestId")).isEqualTo("7df393d7-5fc1-3097-b6ab-0bbb88e3fa27");
    }

    @Test
    @DisplayName("property event relay job은 누락되거나 안전하지 않은 scheduler execution id를 거부한다")
    void propertyEventRelayRejectsInvalidSchedulerExecutionIdentity() {
        assertThatThrownBy(() -> BatchJobArguments.from("propertyEventRelayJob", Map.of(), clock))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
        assertThatThrownBy(() -> BatchJobArguments.from(
                        "propertyEventRelayJob", Map.of("schedulerExecutionId", "../unsafe"), clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("schedulerExecutionId");
    }

    @Test
    @DisplayName("outbox retention job도 scheduler execution id에서 독립적인 canonical UUID를 만든다")
    void propertyEventOutboxRetentionUsesSchedulerExecutionIdentity() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "propertyEventOutboxRetentionJob", Map.of("schedulerExecutionId", "d32c5kddcf5bb8c3"), clock);

        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
        assertThat(arguments.jobParameters().getString("requestId"))
                .isNotEqualTo(BatchJobArguments.from(
                                "propertyEventRelayJob", Map.of("schedulerExecutionId", "d32c5kddcf5bb8c3"), clock)
                        .jobParameters()
                        .getString("requestId"));
    }

    @Test
    @DisplayName("뉴스 최초 수집은 BOOTSTRAP 접두어와 canonical UUID를 식별자로 보존한다")
    void marketNewsGeneralAcceptsBootstrapRequestId() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "marketNewsGeneralJob", Map.of("requestId", "BOOTSTRAP:123e4567-e89b-12d3-a456-426614174012"), clock);

        assertThat(arguments.jobParameters().getString("requestId"))
                .isEqualTo("BOOTSTRAP:123e4567-e89b-12d3-a456-426614174012");
        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
    }

    @Test
    @DisplayName("뉴스 회수 job은 snapshot UUID와 안정적인 품질 실패 사유만 허용한다")
    void marketNewsWithdrawalValidatesSnapshotAndReason() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "marketNewsWithdrawalJob",
                Map.of(
                        "snapshotId", "123e4567-e89b-12d3-a456-426614174013",
                        "reason", "RELATION_ACCURACY_BELOW_THRESHOLD"),
                clock);

        assertThat(arguments.jobParameters().getString("snapshotId")).isEqualTo("123e4567-e89b-12d3-a456-426614174013");
        assertThat(arguments.jobParameters().getString("reason")).isEqualTo("RELATION_ACCURACY_BELOW_THRESHOLD");
        assertThatThrownBy(() -> BatchJobArguments.from(
                        "marketNewsWithdrawalJob",
                        Map.of(
                                "snapshotId", "123e4567-e89b-12d3-a456-426614174013",
                                "reason", "FREE_TEXT"),
                        clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("supported market news withdrawal reason");
    }

    @Test
    @DisplayName("뉴스 품질 표본 job은 review set UUID와 versioned policy만 허용한다")
    void marketNewsQualitySampleValidatesReviewSetAndPolicyVersion() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "marketNewsQualitySampleJob",
                Map.of(
                        "reviewSetId", "123e4567-e89b-12d3-a456-426614174014",
                        "policyVersion", "NEWS_V2"),
                clock);

        assertThat(arguments.jobParameters().getString("reviewSetId"))
                .isEqualTo("123e4567-e89b-12d3-a456-426614174014");
        assertThat(arguments.jobParameters().getString("policyVersion")).isEqualTo("NEWS_V2");
        assertThatThrownBy(() -> BatchJobArguments.from(
                        "marketNewsQualitySampleJob",
                        Map.of(
                                "reviewSetId", "123e4567-e89b-12d3-a456-426614174014",
                                "policyVersion", "../NEWS"),
                        clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("policyVersion");
    }

    @Test
    @DisplayName("뉴스 운영 schedule은 execution id를 job별 canonical requestId로 변환한다")
    void marketNewsSchedulesUseNamespacedExecutionIdentity() {
        BatchJobArguments morning = BatchJobArguments.from(
                "marketNewsMorningJob", Map.of("schedulerExecutionId", "schedule-run-20260725"), clock);
        BatchJobArguments retention = BatchJobArguments.from(
                "marketNewsRetentionJob", Map.of("schedulerExecutionId", "schedule-run-20260725"), clock);

        assertThat(morning.jobParameters().getString("requestId"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(retention.jobParameters().getString("requestId"))
                .isNotEqualTo(morning.jobParameters().getString("requestId"));
        assertThat(morning.jobParameters().getString("runDate")).isEqualTo("2026-07-07");
    }

    @Test
    @DisplayName("daily job은 canonical UUID requestId를 identifying parameter로 보존한다")
    void dailyJobKeepsRequiredCanonicalRequestId() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "rtmsDailyRefreshJob",
                Map.of(
                        "runDate", "2026-07-10",
                        "requestId", "123e4567-e89b-12d3-a456-426614174001"),
                clock);

        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-10");
        assertThat(arguments.jobParameters().getString("requestId")).isEqualTo("123e4567-e89b-12d3-a456-426614174001");
        assertThat(arguments.jobParameters().getParameter("requestId").identifying())
                .isTrue();
    }

    @Test
    @DisplayName("daily job은 완료 처리된 JobInstance 복구용 restartAttempt를 식별자로 보존한다")
    void dailyJobKeepsOptionalRestartAttempt() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "rtmsDailyRefreshJob",
                Map.of(
                        "runDate", "2026-07-10",
                        "requestId", "123e4567-e89b-12d3-a456-426614174009",
                        "restartAttempt", "1"),
                clock);

        assertThat(arguments.jobParameters().getString("restartAttempt")).isEqualTo("1");
        assertThat(arguments.jobParameters().getParameter("restartAttempt").identifying())
                .isTrue();
    }

    @Test
    @DisplayName("backfill job은 기간, 지역, requestId를 모두 요구한다")
    void backfillJobRequiresExplicitParameters() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "rtmsBackfillJob",
                Map.of(
                        "fromYmd", "202606",
                        "toYmd", "202607",
                        "lawdCds", "11680,11710",
                        "requestId", "123e4567-e89b-12d3-a456-426614174002"),
                clock);

        assertThat(arguments.jobParameters().getString("fromYmd")).isEqualTo("202606");
        assertThat(arguments.jobParameters().getString("toYmd")).isEqualTo("202607");
        assertThat(arguments.jobParameters().getString("lawdCds")).isEqualTo("11680,11710");
        assertThat(arguments.jobParameters().getString("requestId")).isEqualTo("123e4567-e89b-12d3-a456-426614174002");
    }

    @Test
    @DisplayName("daily job은 requestId 누락과 arbitrary text를 exit code 2로 거부한다")
    void dailyJobRejectsMissingOrNonCanonicalRequestId() {
        assertThatThrownBy(() -> BatchJobArguments.from("rtmsDailyRefreshJob", Map.of(), clock))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
        assertThatThrownBy(() ->
                        BatchJobArguments.from("rtmsDailyRefreshJob", Map.of("requestId", "hs-sep-live-1"), clock))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("지원하지 않는 job 이름은 exit code 2 예외로 거부한다")
    void rejectsUnsupportedJobName() {
        assertThatThrownBy(() -> BatchJobArguments.from("unknownJob", Map.of(), clock))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("backfill 기간 역전은 거부한다")
    void rejectsBackfillReversedRange() {
        assertThatThrownBy(() -> BatchJobArguments.from(
                        "rtmsBackfillJob",
                        Map.of(
                                "fromYmd", "202607",
                                "toYmd", "202606",
                                "lawdCds", "11680",
                                "requestId", "123e4567-e89b-12d3-a456-426614174003"),
                        clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("fromYmd");
    }

    @Test
    @DisplayName("building metadata job은 mode, quota cap 입력, 범위, canonical requestId를 보존한다")
    void parsesBuildingMetadataJobArguments() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "complexBuildingMetadataJob",
                Map.of(
                        "mode",
                        "missing",
                        "runDate",
                        "2026-07-10",
                        "maxRequests",
                        "900",
                        "fromComplexId",
                        "100",
                        "toComplexId",
                        "200",
                        "requestId",
                        "123e4567-e89b-12d3-a456-426614174004"),
                clock);

        assertThat(arguments.jobParameters().getString("mode")).isEqualTo("missing");
        assertThat(arguments.jobParameters().getString("maxRequests")).isEqualTo("900");
        assertThat(arguments.jobParameters().getString("fromComplexId")).isEqualTo("100");
    }

    @Test
    @DisplayName("ODC gap-fill job은 maxTargets, cutoff, canonical requestId를 요구한다")
    void parsesOdcMetadataGapFillArguments() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "complexOdcMetadataGapFillJob",
                Map.of(
                        "runDate",
                        "2026-07-10",
                        "maxTargets",
                        "450",
                        "fromComplexId",
                        "100",
                        "toComplexId",
                        "200",
                        "requestId",
                        "123e4567-e89b-12d3-a456-426614174005"),
                clock);

        assertThat(arguments.jobParameters().getString("maxTargets")).isEqualTo("450");
        assertThat(arguments.jobParameters().getString("toComplexId")).isEqualTo("200");
    }

    @Test
    @DisplayName("ODC gap-fill job은 cutoff 누락을 거부한다")
    void odcMetadataGapFillRequiresCutoff() {
        assertThatThrownBy(() -> BatchJobArguments.from(
                        "complexOdcMetadataGapFillJob",
                        Map.of("maxTargets", "20", "requestId", "123e4567-e89b-12d3-a456-426614174005"),
                        clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("toComplexId");
    }

    @Test
    @DisplayName("building register 수집 job은 고정 campaign과 adaptive 전략 입력을 보존한다")
    void parsesBuildingRegisterCollectionArguments() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "complexBuildingRegisterCollectJob",
                Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174010",
                        "requestId", "123e4567-e89b-12d3-a456-426614174011",
                        "runDate", "2026-07-20",
                        "mode", "missing",
                        "strategy", "adaptive",
                        "maxRequests", "900",
                        "parallelism", "3",
                        "toComplexId", "200"),
                clock);

        assertThat(arguments.jobParameters().getString("collectionId"))
                .isEqualTo("123e4567-e89b-12d3-a456-426614174010");
        assertThat(arguments.jobParameters().getString("strategy")).isEqualTo("adaptive");
        assertThat(arguments.jobParameters().getString("parallelism")).isEqualTo("3");
        assertThat(arguments.jobParameters().getString("toComplexId")).isEqualTo("200");
    }

    @Test
    @DisplayName("building register 수집 job은 parallelism 기본값과 상한을 강제한다")
    void defaultsAndBoundsBuildingRegisterParallelism() {
        Map<String, String> required = Map.of(
                "collectionId", "123e4567-e89b-12d3-a456-426614174010",
                "requestId", "123e4567-e89b-12d3-a456-426614174011",
                "runDate", "2026-07-20",
                "mode", "missing",
                "strategy", "adaptive",
                "maxRequests", "25",
                "toComplexId", "200");

        assertThat(BatchJobArguments.from("complexBuildingRegisterCollectJob", required, clock)
                        .jobParameters()
                        .getString("parallelism"))
                .isEqualTo("1");
        Map<String, String> tooWide = new java.util.HashMap<>(required);
        tooWide.put("parallelism", "5");
        assertThatThrownBy(() -> BatchJobArguments.from("complexBuildingRegisterCollectJob", tooWide, clock))
                .isInstanceOf(BatchExitCodeException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    @DisplayName("building ratio 투영 job은 완료 campaign과 적용 상한을 요구한다")
    void parsesBuildingRatioProjectionArguments() {
        BatchJobArguments arguments = BatchJobArguments.from(
                "complexBuildingRatioProjectJob",
                Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174010",
                        "requestId", "123e4567-e89b-12d3-a456-426614174012",
                        "runDate", "2026-07-20",
                        "maxTargets", "100"),
                clock);

        assertThat(arguments.jobParameters().getString("maxTargets")).isEqualTo("100");
        assertThat(arguments.jobParameters().getString("runDate")).isEqualTo("2026-07-20");
    }

    @Test
    @DisplayName("building profile 운영 job 5종의 identifying parameter를 허용하고 보존한다")
    void parsesBuildingProfileJobArguments() {
        BatchJobArguments replay = BatchJobArguments.from(
                "complexBuildingRegisterProfileReplayJob",
                Map.of(
                        "sourceCollectionId", "123e4567-e89b-12d3-a456-426614174020",
                        "parseRunId", "123e4567-e89b-12d3-a456-426614174021",
                        "parserVersion", "PROFILE_V1",
                        "maxPages", "100"),
                clock);
        BatchJobArguments collect = BatchJobArguments.from(
                "complexBuildingRegisterProfileCollectJob",
                Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174022",
                        "requestId", "123e4567-e89b-12d3-a456-426614174023",
                        "runDate", "2026-07-21",
                        "purpose", "profile-discovery",
                        "targetScope", "validation-sample",
                        "strategy", "compare-recap-title",
                        "sampleSize", "1500",
                        "selectionSeed", "profile-v1-fixed-seed",
                        "maxRequests", "900",
                        "parallelism", "2"),
                clock);
        BatchJobArguments analyze = BatchJobArguments.from(
                "complexBuildingRegisterProfileAnalyzeJob",
                Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174022",
                        "parseRunId", "123e4567-e89b-12d3-a456-426614174024",
                        "analysisRunId", "123e4567-e89b-12d3-a456-426614174025",
                        "rulesVersion", "PROFILE_V1",
                        "outputDirectory", "/tmp/profile-report"),
                clock);
        BatchJobArguments legalImport = BatchJobArguments.from(
                "legalDongCodeMappingImportJob",
                Map.of(
                        "importId", "123e4567-e89b-12d3-a456-426614174026",
                        "effectiveDate", "2026-07-01",
                        "sourceFile", "/tmp/legal-dong.csv"),
                clock);
        BatchJobArguments project = BatchJobArguments.from(
                "complexBuildingRegisterProfileProjectJob",
                Map.of(
                        "projectionRunId", "123e4567-e89b-12d3-a456-426614174027",
                        "analysisRunId", "123e4567-e89b-12d3-a456-426614174025",
                        "projectionVersion", "PROFILE_PROJECTION_V1"),
                clock);

        assertThat(replay.jobParameters().getString("parserVersion")).isEqualTo("PROFILE_V1");
        assertThat(replay.jobParameters().getString("maxPages")).isEqualTo("100");
        assertThat(collect.jobParameters().getString("sampleSize")).isEqualTo("1500");
        assertThat(collect.jobParameters().getString("parallelism")).isEqualTo("2");
        assertThat(analyze.jobParameters().getString("outputDirectory")).isEqualTo("/tmp/profile-report");
        assertThat(legalImport.jobParameters().getString("effectiveDate")).isEqualTo("2026-07-01");
        assertThat(project.jobParameters().getString("projectionVersion")).isEqualTo("PROFILE_PROJECTION_V1");
    }

    @Test
    @DisplayName("전국 profile 수집은 표본 크기 없이 nationwide staging 범위를 보존한다")
    void parsesNationwideBuildingProfileCollectionArguments() {
        BatchJobArguments collect = BatchJobArguments.from(
                "complexBuildingRegisterProfileCollectJob",
                Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174027",
                        "requestId", "123e4567-e89b-12d3-a456-426614174028",
                        "runDate", "2026-07-22",
                        "purpose", "profile-discovery",
                        "targetScope", "nationwide-staging",
                        "strategy", "compare-recap-title",
                        "selectionSeed", "profile-nationwide-v1",
                        "maxRequests", "300000",
                        "parallelism", "3"),
                clock);

        assertThat(collect.jobParameters().getString("targetScope")).isEqualTo("nationwide-staging");
        assertThat(collect.jobParameters().getString("sampleSize")).isNull();
        assertThat(collect.jobParameters().getString("parallelism")).isEqualTo("3");
    }
}
