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
}
