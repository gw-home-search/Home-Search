package com.home.application.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.insight.collection.RtmsCollectionExecutionPlan;
import com.home.application.insight.collection.RtmsCollectionExecutionRepository;
import com.home.application.insight.collection.RtmsCollectionExecutionService;
import com.home.application.insight.collection.RtmsCollectionWorkUnitPlan;
import com.home.application.insight.generation.MarketInsightBuildRepository;
import com.home.application.insight.generation.MarketInsightDailyBuildService;
import com.home.application.insight.generation.MarketInsightRolling7dBuildService;
import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.application.insight.generation.MarketInsightWeeklyBuildService;
import com.home.application.insight.read.InvalidInsightQueryException;
import com.home.application.insight.read.MarketInsightQueryService;
import com.home.application.insight.read.MarketInsightReadRepository;
import com.home.application.insight.read.MarketInsightSnapshotView;
import com.home.application.insight.read.MarketInsightTradeItemView;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.MarketInsightBuildStatus;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.MarketInsightDataStatus;
import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightPeriodType;
import com.home.domain.insight.MarketInsightRejectionReason;
import com.home.domain.insight.MarketInsightScopeType;
import com.home.domain.insight.MarketInsightTradeStatus;
import com.home.domain.insight.RtmsCollectionExecutionState;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketInsightApplicationServicesTest {

    private static final LocalDate RUN_DATE = LocalDate.parse("2026-07-22");
    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174088");

    @Test
    @DisplayName("daily build는 source 없음, coverage 거부, 정상 발행을 구분한다")
    void dailyBuildDistinguishesUnavailableRejectedAndPublishedSources() {
        MarketInsightBuildRepository repository = mock(MarketInsightBuildRepository.class);
        MarketInsightDailyBuildService service = new MarketInsightDailyBuildService(repository);
        UUID missingSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174081");
        when(repository.findLatestDailyNationwide(RUN_DATE)).thenReturn(Optional.empty());
        when(repository.rejectDailyNationwide(
                        eq(RUN_DATE),
                        isNull(),
                        eq(MarketInsightRejectionReason.INCOMPLETE_WORKSET),
                        any(Instant.class)))
                .thenReturn(missingSnapshotId);

        var missing = service.build(RUN_DATE);

        assertThat(missing.published()).isFalse();
        assertThat(missing.rejectionReason()).isEqualTo(MarketInsightRejectionReason.INCOMPLETE_WORKSET);

        MarketInsightSourceExecution incomplete = source(
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 2, 1, 0, 0));
        UUID rejectedSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174082");
        when(repository.findLatestDailyNationwide(RUN_DATE)).thenReturn(Optional.of(incomplete));
        when(repository.rejectDailyNationwide(
                        eq(RUN_DATE),
                        eq(incomplete),
                        eq(MarketInsightRejectionReason.INCOMPLETE_WORKSET),
                        any(Instant.class)))
                .thenReturn(rejectedSnapshotId);

        var rejected = service.build(RUN_DATE);

        assertThat(rejected.snapshotId()).isEqualTo(rejectedSnapshotId);
        assertThat(rejected.published()).isFalse();

        MarketInsightSourceExecution complete = source(
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 1, 1, 0, 0));
        UUID publishedSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174083");
        when(repository.findLatestDailyNationwide(RUN_DATE)).thenReturn(Optional.of(complete));
        when(repository.publishDailyNationwide(eq(complete), any(Instant.class)))
                .thenReturn(publishedSnapshotId);

        var published = service.build(RUN_DATE);

        assertThat(published.snapshotId()).isEqualTo(publishedSnapshotId);
        assertThat(published.published()).isTrue();
        assertThat(published.rejectionReason()).isNull();
        assertThatThrownBy(() -> service.build(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("weekly build는 ISO Monday의 완전한 7개 DAILY execution만 발행한다")
    void weeklyBuildRequiresSevenCompleteDailyExecutions() {
        LocalDate weekStart = LocalDate.parse("2026-07-13");
        MarketInsightBuildRepository repository = mock(MarketInsightBuildRepository.class);
        MarketInsightWeeklyBuildService service = new MarketInsightWeeklyBuildService(repository);
        List<MarketInsightSourceExecution> sixDays = java.util.stream.IntStream.range(0, 6)
                .mapToObj(day -> source(
                        weekStart.plusDays(day),
                        new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 1, 1, 0, 0)))
                .toList();
        UUID rejectedId = UUID.fromString("123e4567-e89b-12d3-a456-426614174084");
        when(repository.findLatestDailyNationwideForWeek(weekStart)).thenReturn(sixDays);
        when(repository.rejectWeeklyNationwide(
                        eq(weekStart),
                        eq(sixDays),
                        eq(MarketInsightRejectionReason.INCOMPLETE_WORKSET),
                        any(Instant.class)))
                .thenReturn(rejectedId);

        assertThat(service.build(weekStart).published()).isFalse();

        List<MarketInsightSourceExecution> sevenDays = java.util.stream.IntStream.range(0, 7)
                .mapToObj(day -> source(
                        weekStart.plusDays(day),
                        new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 1, 1, 0, 0)))
                .toList();
        UUID publishedId = UUID.fromString("123e4567-e89b-12d3-a456-426614174085");
        when(repository.findLatestDailyNationwideForWeek(weekStart)).thenReturn(sevenDays);
        when(repository.publishWeeklyNationwide(eq(weekStart), eq(sevenDays), any(Instant.class)))
                .thenReturn(publishedId);

        assertThat(service.build(weekStart).snapshotId()).isEqualTo(publishedId);
        assertThatThrownBy(() -> service.build(LocalDate.parse("2026-07-14")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monday");
    }

    @Test
    @DisplayName("rolling 7일 build는 당일 최신 DAILY 실행 하나만으로 발행한다")
    void rollingBuildUsesOneCompleteExecutionForRunDate() {
        MarketInsightBuildRepository repository = mock(MarketInsightBuildRepository.class);
        MarketInsightRolling7dBuildService service = new MarketInsightRolling7dBuildService(repository);
        MarketInsightSourceExecution complete = source(
                RUN_DATE,
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 2, 2, 0, 0));
        UUID publishedId = UUID.fromString("123e4567-e89b-12d3-a456-426614174086");
        when(repository.findLatestDailyNationwide(RUN_DATE)).thenReturn(Optional.of(complete));
        when(repository.publishRolling7dNationwide(eq(complete), any(Instant.class)))
                .thenReturn(publishedId);

        var result = service.build(RUN_DATE);

        assertThat(result.published()).isTrue();
        assertThat(result.snapshotId()).isEqualTo(publishedId);
        verify(repository).publishRolling7dNationwide(eq(complete), any(Instant.class));
    }

    @Test
    @DisplayName("latest query는 FRESH, STALE, UNAVAILABLE과 입력 오류를 명확히 구분한다")
    void latestQueryDistinguishesDataStatusesAndValidatesScope() {
        MarketInsightReadRepository repository = mock(MarketInsightReadRepository.class);
        MarketInsightQueryService service = new MarketInsightQueryService(repository);
        MarketInsightSnapshotView snapshot = snapshot(RUN_DATE);
        when(repository.existsRootSidoCode("11")).thenReturn(true);
        when(repository.existsRootSidoCode("99")).thenReturn(false);
        when(repository.findLatestDaily(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 10))
                .thenReturn(Optional.of(snapshot));

        var fresh = service.latest(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 10);
        var stale = service.latest(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE.plusDays(1), 10);
        var unavailable = service.latest(MarketInsightScopeType.SIDO, "11", RUN_DATE, 10);

        assertThat(fresh.dataStatus()).isEqualTo(MarketInsightDataStatus.FRESH);
        assertThat(fresh.newTrades()).hasSize(1);
        assertThat(fresh.highestDeals()).hasSize(1);
        assertThat(fresh.recordHighs()).hasSize(1);
        assertThat(fresh.previousRises()).hasSize(1);
        assertThat(fresh.previousFalls()).hasSize(1);
        assertThat(fresh.cancellations()).hasSize(1);
        assertThat(stale.dataStatus()).isEqualTo(MarketInsightDataStatus.UNAVAILABLE);
        assertThat(unavailable.dataStatus()).isEqualTo(MarketInsightDataStatus.UNAVAILABLE);
        assertThat(unavailable.regionCode()).isEqualTo("11");

        when(repository.findLatestDaily(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE.plusDays(1), 10))
                .thenReturn(Optional.of(snapshot));
        assertThat(service.latest(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE.plusDays(1), 10)
                        .dataStatus())
                .isEqualTo(MarketInsightDataStatus.STALE);

        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 0))
                .isInstanceOf(InvalidInsightQueryException.class);
        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 51))
                .isInstanceOf(InvalidInsightQueryException.class);
        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.NATIONWIDE, "11", RUN_DATE, 10))
                .isInstanceOf(InvalidInsightQueryException.class);
        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.SIDO, " ", RUN_DATE, 10))
                .isInstanceOf(InvalidInsightQueryException.class);
        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.SIDO, "99", RUN_DATE, 10))
                .isInstanceOf(InvalidInsightQueryException.class)
                .hasMessageContaining("root SIDO");
        assertThatThrownBy(() -> service.latest(null, null, RUN_DATE, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.latest(MarketInsightScopeType.NATIONWIDE, null, null, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rolling 조회는 최신 DAILY 실행과 source가 다르면 기간을 이동하지 않고 STALE을 반환한다")
    void rollingQueryUsesSourceFreshnessInsteadOfWallClockDate() {
        MarketInsightReadRepository repository = mock(MarketInsightReadRepository.class);
        MarketInsightQueryService service = new MarketInsightQueryService(repository);
        MarketInsightSnapshotView staleSnapshot = new MarketInsightSnapshotView(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174089"),
                LocalDate.parse("2026-07-17"),
                LocalDate.parse("2026-07-23"),
                Instant.parse("2026-07-23T00:04:00Z"),
                Instant.parse("2026-07-23T00:03:00Z"),
                MarketInsightScopeType.NATIONWIDE,
                null,
                com.home.domain.insight.MarketInsightQuality.NONE,
                false,
                List.of());
        when(repository.findLatestRolling7d(MarketInsightScopeType.NATIONWIDE, null, 10))
                .thenReturn(Optional.of(staleSnapshot));

        var result = service.weekly(MarketInsightScopeType.NATIONWIDE, null, LocalDate.parse("2026-07-24"), 10);

        assertThat(result.dataStatus()).isEqualTo(MarketInsightDataStatus.STALE);
        assertThat(result.periodStart()).isEqualTo(LocalDate.parse("2026-07-17"));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-07-23"));
    }

    @Test
    @DisplayName("collection service는 work unit 상태 전이를 repository에 위임하고 non-terminal 완료를 거부한다")
    void collectionServiceOrchestratesDurableTransitions() {
        RtmsCollectionExecutionRepository repository = mock(RtmsCollectionExecutionRepository.class);
        RtmsCollectionExecutionService service = new RtmsCollectionExecutionService(repository);
        ExecutionCorrelationId executionId = ExecutionCorrelationId.from(EXECUTION_ID.toString());
        List<RtmsCollectionWorkUnitPlan> workUnits = List.of(new RtmsCollectionWorkUnitPlan("11680", "202607"));
        when(repository.findWorkUnitState(executionId, "11680", "202607"))
                .thenReturn(RtmsCollectionWorkUnitState.RUNNING);
        MarketInsightCoverage coverage =
                new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 1, 1, 0, 0);
        when(repository.finish(eq(executionId), any(Instant.class))).thenReturn(coverage);

        service.plan(executionId, RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, RUN_DATE, workUnits);
        assertThat(service.state(executionId, "11680", "202607")).isEqualTo(RtmsCollectionWorkUnitState.RUNNING);
        service.markRunning(executionId, "11680", "202607");
        service.markTerminal(executionId, "11680", "202607", RtmsCollectionWorkUnitState.COMPLETED, 7L);
        assertThat(service.finish(executionId)).isEqualTo(coverage);

        ArgumentCaptor<RtmsCollectionExecutionPlan> plan = ArgumentCaptor.forClass(RtmsCollectionExecutionPlan.class);
        verify(repository).savePlan(plan.capture(), any(Instant.class));
        assertThat(plan.getValue().workUnits()).containsExactlyElementsOf(workUnits);
        verify(repository).markRunning(eq(executionId), eq("11680"), eq("202607"), any(Instant.class));
        verify(repository)
                .markTerminal(
                        eq(executionId),
                        eq("11680"),
                        eq("202607"),
                        eq(RtmsCollectionWorkUnitState.COMPLETED),
                        eq(7L),
                        any(Instant.class));
        assertThatThrownBy(() ->
                        service.markTerminal(executionId, "11680", "202607", RtmsCollectionWorkUnitState.RUNNING, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markTerminal(executionId, "11680", "202607", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("persisted insight enum은 한국어 metadata와 상태 predicate를 제공한다")
    void persistedEnumsExposeMetadataAndPredicates() {
        assertThat(Arrays.stream(MarketInsightMetricType.values()).map(MarketInsightMetricType::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(MarketInsightMetricType.values()).map(MarketInsightMetricType::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(MarketInsightRejectionReason.values()).map(MarketInsightRejectionReason::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionMode.values()).map(RtmsCollectionMode::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionScopeType.values()).map(RtmsCollectionScopeType::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(MarketInsightScopeType.values()).map(MarketInsightScopeType::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(MarketInsightDataStatus.values()).map(MarketInsightDataStatus::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(MarketInsightTradeStatus.values()).map(MarketInsightTradeStatus::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(MarketInsightPeriodType.values()).map(MarketInsightPeriodType::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(MarketInsightBuildStatus.values()).map(MarketInsightBuildStatus::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionExecutionState.values()).map(RtmsCollectionExecutionState::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionExecutionState.values())
                        .map(RtmsCollectionExecutionState::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionWorkUnitState.values()).map(RtmsCollectionWorkUnitState::titleKo))
                .allSatisfy(title -> assertThat(title).isNotBlank());
        assertThat(Arrays.stream(RtmsCollectionWorkUnitState.values()).map(RtmsCollectionWorkUnitState::descriptionKo))
                .allSatisfy(description -> assertThat(description).isNotBlank());

        assertThat(RtmsCollectionMode.DAILY.qualifiesForDailyInsight()).isTrue();
        assertThat(RtmsCollectionMode.BACKFILL.qualifiesForDailyInsight()).isFalse();
        assertThat(RtmsCollectionScopeType.NATIONWIDE.qualifiesForNationwideInsight())
                .isTrue();
        assertThat(RtmsCollectionScopeType.TARGETED.qualifiesForNationwideInsight())
                .isFalse();
        assertThat(RtmsCollectionExecutionState.COMPLETED.terminal()).isTrue();
        assertThat(RtmsCollectionExecutionState.RUNNING.terminal()).isFalse();
        assertThat(RtmsCollectionWorkUnitState.PARTIAL.terminal()).isTrue();
        assertThat(RtmsCollectionWorkUnitState.PLANNED.terminal()).isFalse();
        assertThat(RtmsCollectionWorkUnitState.COMPLETED.successful()).isTrue();
        assertThat(RtmsCollectionWorkUnitState.FAILED.successful()).isFalse();
        assertThat(MarketInsightBuildStatus.BUILDING.canTransitionTo(MarketInsightBuildStatus.PUBLISHED))
                .isTrue();
        assertThat(MarketInsightBuildStatus.PUBLISHED.canTransitionTo(MarketInsightBuildStatus.SUPERSEDED))
                .isTrue();
        assertThat(MarketInsightBuildStatus.SUPERSEDED.canTransitionTo(MarketInsightBuildStatus.PUBLISHED))
                .isFalse();
    }

    private static MarketInsightSourceExecution source(MarketInsightCoverage coverage) {
        return source(RUN_DATE, coverage);
    }

    private static MarketInsightSourceExecution source(LocalDate runDate, MarketInsightCoverage coverage) {
        return new MarketInsightSourceExecution(EXECUTION_ID, runDate, Instant.parse("2026-07-22T00:03:00Z"), coverage);
    }

    private static MarketInsightSnapshotView snapshot(LocalDate periodStart) {
        List<MarketInsightTradeItemView> items = List.of(
                item(MarketInsightMetricType.DAILY_NEW_TRADE),
                item(MarketInsightMetricType.DAILY_HIGHEST_DEAL),
                item(MarketInsightMetricType.AREA_RECORD_HIGH),
                item(MarketInsightMetricType.AREA_PREVIOUS_RISE),
                item(MarketInsightMetricType.AREA_PREVIOUS_FALL),
                item(MarketInsightMetricType.CANCELLATION_CORRECTION));
        return new MarketInsightSnapshotView(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174087"),
                periodStart,
                periodStart,
                Instant.parse("2026-07-22T00:04:00Z"),
                Instant.parse("2026-07-22T00:03:00Z"),
                MarketInsightScopeType.NATIONWIDE,
                null,
                items);
    }

    private static MarketInsightTradeItemView item(MarketInsightMetricType metricType) {
        return new MarketInsightTradeItemView(
                metricType,
                1,
                501L,
                1001L,
                "Sample Apartment",
                "Seoul",
                "Gangnam-gu",
                new BigDecimal("84.99"),
                125000L,
                LocalDate.parse("2026-07-01"),
                Instant.parse("2026-07-22T00:01:00Z"),
                120000L,
                LocalDate.parse("2026-06-01"),
                5000L,
                new BigDecimal("4.166667"),
                2,
                1,
                3,
                MarketInsightTradeStatus.ACTIVE,
                null);
    }
}
