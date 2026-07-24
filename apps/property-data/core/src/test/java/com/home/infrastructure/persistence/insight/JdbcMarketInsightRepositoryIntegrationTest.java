package com.home.infrastructure.persistence.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightRejectionReason;
import com.home.domain.insight.MarketInsightScopeType;
import com.home.domain.insight.MarketInsightTradeStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMarketInsightRepositoryIntegrationTest extends JdbcPostgresTestSupport {

    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174099");
    private static final LocalDate RUN_DATE = LocalDate.parse("2026-07-22");

    private JdbcMarketInsightBuildRepository buildRepository;
    private JdbcMarketInsightReadRepository readRepository;

    @BeforeEach
    void setUpInsightEvidence() {
        seedPropertyExplorationData();
        seedRemainingSidoRegions();
        jdbcClient.sql("UPDATE complex SET region_id = 111 WHERE id = 501").update();
        buildRepository = new JdbcMarketInsightBuildRepository(jdbcClient);
        readRepository = new JdbcMarketInsightReadRepository(jdbcClient);

        OffsetDateTime startedAt = offset("2026-07-22T00:00:00Z");
        OffsetDateTime completedAt = offset("2026-07-22T00:03:00Z");
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_execution (
                        execution_id, collection_mode, scope_type, run_date, state,
                        planned_work_unit_count, started_at, completed_at
                    ) VALUES (
                        :executionId, 'DAILY', 'NATIONWIDE', :runDate, 'COMPLETED',
                        1, :startedAt, :completedAt
                    )
                    """)
                .param("executionId", EXECUTION_ID)
                .param("runDate", RUN_DATE)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .update();
        long ingestRunId = jdbcClient
                .sql("""
                    INSERT INTO rtms_ingest_run (
                        lawd_cd, deal_ymd, status, page_count, read_count, raw_saved_count,
                        normalized_inserted_count, duplicate_skipped_count, canceled_skipped_count,
                        match_failed_count, parse_failed_count, started_at, completed_at,
                        execution_correlation_id
                    ) VALUES (
                        '11680', '202607', 'COMPLETED', 1, 2, 2, 2, 0, 0, 0, 0,
                        :startedAt, :completedAt, :executionId
                    ) RETURNING id
                    """)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .param("executionId", EXECUTION_ID)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_work_unit (
                        execution_id, lawd_cd, deal_ymd, state, rtms_ingest_run_id,
                        started_at, completed_at
                    ) VALUES (
                        :executionId, '11680', '202607', 'COMPLETED', :ingestRunId,
                        :startedAt, :completedAt
                    )
                    """)
                .param("executionId", EXECUTION_ID)
                .param("ingestRunId", ingestRunId)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .update();
        jdbcClient.sql("""
                    UPDATE raw_trade_ingest
                    SET execution_correlation_id = :executionId,
                        registration_date_raw = CASE id
                            WHEN 90001 THEN '26.07.17'
                            ELSE '26.07.22'
                        END,
                        registration_date = CASE id
                            WHEN 90001 THEN DATE '2026-07-17'
                            ELSE DATE '2026-07-22'
                        END,
                        processed_at = CASE id
                            WHEN 90001 THEN TIMESTAMPTZ '2026-07-22 00:01:00Z'
                            ELSE TIMESTAMPTZ '2026-07-22 00:02:00Z'
                        END
                    """).param("executionId", EXECUTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO trade_source_key_registry (
                        source, source_key, raw_ingest_id, trade_id, trade_deal_date
                    ) VALUES
                        ('RTMS', 'sample-rtms-20251201', 90001, 9001, DATE '2025-12-01'),
                        ('RTMS', 'sample-rtms-20251215', 90002, 9002, DATE '2025-12-15')
                    """).update();
    }

    @Test
    @DisplayName("완결된 DAILY 실행은 idempotent snapshot으로 발행되고 public read model로 조회된다")
    void publishesAndReadsDailySnapshotIdempotently() {
        seedExactAreaAndCancellationEvidence();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID snapshotId = buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));
        UUID repeatedSnapshotId = buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:05:00Z"));

        assertThat(repeatedSnapshotId).isEqualTo(snapshotId);
        assertThat(source.coverage().completedCount()).isEqualTo(1);
        assertThat(source.completedAt()).isEqualTo(Instant.parse("2026-07-22T00:03:00Z"));

        jdbcClient
                .sql("UPDATE trade SET deleted_at = TIMESTAMPTZ '2026-07-22 00:06:00Z' WHERE id = 9002")
                .update();

        var snapshot = readRepository
                .findLatestDaily(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 10)
                .orElseThrow();

        assertThat(snapshot.snapshotId()).isEqualTo(snapshotId);
        assertThat(snapshot.scopeType()).isEqualTo(MarketInsightScopeType.NATIONWIDE);
        assertThat(snapshot.regionCode()).isNull();
        assertThat(snapshot.items())
                .extracting(item -> item.metricType())
                .contains(
                        MarketInsightMetricType.DAILY_NEW_TRADE,
                        MarketInsightMetricType.DAILY_HIGHEST_DEAL,
                        MarketInsightMetricType.AREA_RECORD_HIGH,
                        MarketInsightMetricType.AREA_PREVIOUS_RISE,
                        MarketInsightMetricType.AREA_PREVIOUS_FALL,
                        MarketInsightMetricType.CANCELLATION_CORRECTION);
        assertThat(snapshot.items()).allSatisfy(item -> {
            assertThat(item.complexName()).isEqualTo("Sample trade name");
            assertThat(item.sidoName()).isEqualTo("Seoul");
            assertThat(item.sigunguName()).isEqualTo("Gangnam-gu");
        });
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.AREA_RECORD_HIGH)
                .filteredOn(item -> item.dealAmount() == 130000L)
                .filteredOn(item -> item.exclArea().compareTo(new BigDecimal("84.99")) == 0)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.dealAmount()).isEqualTo(130000L);
                    assertThat(item.exclArea()).isEqualByComparingTo("84.99");
                    assertThat(item.previousAmount()).isEqualTo(120000L);
                    assertThat(item.deltaAmount()).isEqualTo(10000L);
                });
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.AREA_PREVIOUS_RISE)
                .filteredOn(item -> item.dealAmount() == 130000L)
                .filteredOn(item -> item.exclArea().compareTo(new BigDecimal("84.99")) == 0)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.previousAmount()).isEqualTo(110000L);
                    assertThat(item.comparisonSampleCount()).isEqualTo(1);
                    assertThat(item.deltaRate()).isEqualByComparingTo("18.181818");
                });
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.AREA_PREVIOUS_FALL)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.previousAmount()).isEqualTo(200000L);
                    assertThat(item.comparisonSampleCount()).isEqualTo(2);
                    assertThat(item.deltaRate()).isEqualByComparingTo("-10.000000");
                });
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.CANCELLATION_CORRECTION)
                .singleElement()
                .satisfies(item -> assertThat(item.tradeStatus()).isEqualTo(MarketInsightTradeStatus.CANCELED));
        assertThat(snapshot.items()).extracting(item -> item.tradeStatus()).contains(MarketInsightTradeStatus.CANCELED);
        assertThat(readRepository.findLatestDaily(MarketInsightScopeType.SIDO, "11", RUN_DATE, 10))
                .isPresent();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_insight_snapshot WHERE build_status = 'PUBLISHED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(18L);
    }

    @Test
    @DisplayName("완결된 7개 DAILY 실행은 lineage를 가진 주간 18 scope로 원자 발행된다")
    void publishesWeeklySnapshotWithSevenExecutionLineages() {
        LocalDate weekStart = LocalDate.parse("2026-07-20");
        seedRemainingWeeklyExecutions(weekStart);

        var sources = buildRepository.findLatestDailyNationwideForWeek(weekStart);
        assertThat(sources).hasSize(7);

        UUID snapshotId =
                buildRepository.publishWeeklyNationwide(weekStart, sources, Instant.parse("2026-07-27T00:04:00Z"));
        UUID repeated =
                buildRepository.publishWeeklyNationwide(weekStart, sources, Instant.parse("2026-07-27T00:05:00Z"));

        assertThat(repeated).isEqualTo(snapshotId);
        assertThat(readRepository.findLatestWeekly(MarketInsightScopeType.NATIONWIDE, null, weekStart, 10))
                .get()
                .satisfies(snapshot -> assertThat(snapshot.items())
                        .extracting(item -> item.metricType())
                        .contains(
                                MarketInsightMetricType.WEEKLY_NEW_TRADE, MarketInsightMetricType.WEEKLY_HIGHEST_DEAL));
        assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM market_insight_snapshot
                            WHERE period_type = 'WEEKLY' AND build_status = 'PUBLISHED'
                            """).query(Long.class).single()).isEqualTo(18L);
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_insight_snapshot_execution")
                        .query(Long.class)
                        .single())
                .isEqualTo(126L);
    }

    @Test
    @DisplayName("당일 완결 실행 하나는 최근 7일 18 scope를 idempotent하게 발행한다")
    void publishesRollingSevenDaySnapshotFromOneCompleteExecution() {
        seedExactAreaAndCancellationEvidence();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID snapshotId = buildRepository.publishRolling7dNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));
        UUID repeated = buildRepository.publishRolling7dNationwide(source, Instant.parse("2026-07-22T00:05:00Z"));

        assertThat(repeated).isEqualTo(snapshotId);
        assertThat(jdbcClient
                        .sql("""
                            SELECT period_start, period_end
                            FROM market_insight_snapshot
                            WHERE snapshot_id = :snapshotId
                            """)
                        .param("snapshotId", snapshotId)
                        .query((rs, rowNum) -> java.util.List.of(
                                rs.getObject("period_start", LocalDate.class),
                                rs.getObject("period_end", LocalDate.class)))
                        .single())
                .containsExactly(LocalDate.parse("2026-07-16"), RUN_DATE);
        assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM market_insight_snapshot
                            WHERE period_type = 'ROLLING_7D' AND build_status = 'PUBLISHED'
                            """).query(Long.class).single()).isEqualTo(18L);
        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*) FROM market_insight_trade_item
                            WHERE snapshot_id = :snapshotId
                              AND metric_type = 'ROLLING_7D_NEW_TRADE'
                            """)
                        .param("snapshotId", snapshotId)
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
        assertThat(readRepository
                        .findLatestRolling7d(MarketInsightScopeType.NATIONWIDE, null, 50)
                        .orElseThrow()
                        .items())
                .filteredOn(item -> item.metricType() != MarketInsightMetricType.CANCELLATION_CORRECTION)
                .allSatisfy(item -> assertThat(item.tradeStatus()).isEqualTo(MarketInsightTradeStatus.ACTIVE));
        assertThat(jdbcClient
                        .sql("""
                            SELECT comparison_trade_id, comparison_trade_deal_date
                            FROM market_insight_trade_item
                            WHERE snapshot_id = :snapshotId
                              AND metric_type = 'AREA_RECORD_HIGH'
                            ORDER BY rank
                            LIMIT 1
                            """)
                        .param("snapshotId", snapshotId)
                        .query((rs, rowNum) -> java.util.List.of(
                                rs.getLong("comparison_trade_id"),
                                rs.getObject("comparison_trade_deal_date", LocalDate.class)))
                        .single())
                .containsExactly(9102L, LocalDate.parse("2025-11-01"));
        assertThat(jdbcClient
                        .sql("""
                            SELECT comparison_trade_id, comparison_trade_deal_date
                            FROM market_insight_trade_item
                            WHERE snapshot_id = :snapshotId
                              AND metric_type = 'AREA_PREVIOUS_RISE'
                              AND trade_id = 9104
                            """)
                        .param("snapshotId", snapshotId)
                        .query((rs, rowNum) -> java.util.List.of(
                                rs.getLong("comparison_trade_id"),
                                rs.getObject("comparison_trade_deal_date", LocalDate.class)))
                        .single())
                .containsExactly(9110L, LocalDate.parse("2026-05-20"));
        assertThat(jdbcClient
                        .sql("""
                            SELECT metric_type
                            FROM market_insight_trade_item
                            WHERE snapshot_id = :snapshotId
                              AND trade_id = 9111
                            """)
                        .param("snapshotId", snapshotId)
                        .query(String.class)
                        .list())
                .isEmpty();
        assertThat(jdbcClient
                        .sql("""
                            SELECT metric_type
                            FROM market_insight_trade_item
                            WHERE snapshot_id = :snapshotId
                              AND trade_id = 9113
                            ORDER BY metric_type
                            """)
                        .param("snapshotId", snapshotId)
                        .query(String.class)
                        .list())
                .containsExactly("ROLLING_7D_HIGHEST_DEAL", "ROLLING_7D_NEW_TRADE");
    }

    @Test
    @DisplayName("최근 7일 계산은 발행 transaction에서 parallel query를 비활성화한다")
    void disablesParallelQueryInsideRollingPublicationTransaction() {
        seedExactAreaAndCancellationEvidence();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        transactionTemplate.executeWithoutResult(status -> {
            buildRepository.publishRolling7dNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));

            assertThat(jdbcClient
                            .sql("SELECT current_setting('max_parallel_workers_per_gather')")
                            .query(Integer.class)
                            .single())
                    .isZero();
        });
    }

    @Test
    @DisplayName("등록일 없는 정상 거래는 계약일 fallback으로 rolling 후보에 포함한다")
    void rollingDeduplicatesSourceIdentityAndPublishesDateQuality() {
        seedRollingQualityEvidence();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID snapshotId = buildRepository.publishRolling7dNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));
        var snapshot = readRepository
                .findLatestRolling7d(MarketInsightScopeType.NATIONWIDE, null, 50)
                .orElseThrow();

        assertThat(snapshot.snapshotId()).isEqualTo(snapshotId);
        assertThat(snapshot.periodStart()).isEqualTo(LocalDate.parse("2026-07-16"));
        assertThat(snapshot.periodEnd()).isEqualTo(RUN_DATE);
        assertThat(snapshot.sourceCurrent()).isTrue();
        assertThat(snapshot.quality().missingRegistrationDateCount()).isEqualTo(1);
        assertThat(snapshot.quality().invalidRegistrationDateCount()).isEqualTo(1);
        assertThat(snapshot.quality().missingCancellationDateCount()).isEqualTo(1);
        assertThat(snapshot.quality().invalidCancellationDateCount()).isEqualTo(1);
        assertThat(snapshot.quality().excludedCount()).isEqualTo(2);
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.ROLLING_7D_NEW_TRADE)
                .hasSize(2)
                .filteredOn(item -> item.registrationDate() == null)
                .extracting(item -> item.dealDate())
                .containsExactly(LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-20"));
        assertThat(snapshot.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.ROLLING_7D_NEW_TRADE)
                .extracting(item -> item.dealDate())
                .doesNotContain(LocalDate.parse("2026-07-22"));
    }

    @Test
    @DisplayName("같은 날짜의 더 최신 정상 실행은 기존 18 scope를 원자적으로 SUPERSEDED한다")
    void newerSameDayExecutionSupersedesPublishedRollingScopes() {
        MarketInsightSourceExecution first =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();
        UUID firstSnapshot = buildRepository.publishRolling7dNationwide(first, Instant.parse("2026-07-22T00:04:00Z"));
        UUID newerExecutionId = seedNewerCompletedExecution("2026-07-22T01:00:00Z");
        MarketInsightSourceExecution newer =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID replacement = buildRepository.publishRolling7dNationwide(newer, Instant.parse("2026-07-22T01:04:00Z"));

        assertThat(newer.executionId()).isEqualTo(newerExecutionId);
        assertThat(replacement).isNotEqualTo(firstSnapshot);
        assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM market_insight_snapshot
                            WHERE period_type = 'ROLLING_7D' AND build_status = 'PUBLISHED'
                            """).query(Long.class).single()).isEqualTo(18L);
        assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM market_insight_snapshot
                            WHERE period_type = 'ROLLING_7D'
                              AND build_status = 'SUPERSEDED'
                              AND superseded_by_snapshot_id IS NOT NULL
                            """).query(Long.class).single()).isEqualTo(18L);
    }

    @Test
    @DisplayName("rolling 18 scope 전환 중 하나라도 실패하면 직전 PUBLISHED 전체가 유지된다")
    void rollingPublicationFailureRollsBackEveryScope() {
        MarketInsightSourceExecution first =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();
        buildRepository.publishRolling7dNationwide(first, Instant.parse("2026-07-22T00:04:00Z"));
        seedNewerCompletedExecution("2026-07-22T01:00:00Z");
        MarketInsightSourceExecution newer =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();
        jdbcClient.sql("""
                    CREATE FUNCTION fail_rolling_publish_for_test()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'forced rolling publication failure';
                    END
                    $$
                    """).update();
        jdbcClient.sql("""
                    CREATE TRIGGER fail_rolling_publish_for_test
                    BEFORE UPDATE OF build_status ON market_insight_snapshot
                    FOR EACH ROW
                    WHEN (OLD.build_status = 'BUILDING' AND NEW.build_status = 'PUBLISHED')
                    EXECUTE FUNCTION fail_rolling_publish_for_test()
                    """).update();
        try {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                            buildRepository.publishRolling7dNationwide(newer, Instant.parse("2026-07-22T01:04:00Z"))))
                    .hasMessageContaining("forced rolling publication failure");

            assertThat(jdbcClient.sql("""
                                SELECT count(*) FROM market_insight_snapshot
                                WHERE period_type = 'ROLLING_7D' AND build_status = 'PUBLISHED'
                                """).query(Long.class).single()).isEqualTo(18L);
            assertThat(jdbcClient.sql("""
                                SELECT count(*) FROM market_insight_snapshot
                                WHERE period_type = 'ROLLING_7D'
                                  AND build_status IN ('BUILDING', 'SUPERSEDED')
                                """).query(Long.class).single()).isZero();
        } finally {
            jdbcClient
                    .sql("DROP TRIGGER IF EXISTS fail_rolling_publish_for_test ON market_insight_snapshot")
                    .update();
            jdbcClient
                    .sql("DROP FUNCTION IF EXISTS fail_rolling_publish_for_test()")
                    .update();
        }
    }

    @Test
    @DisplayName("같은 날짜의 최신 DAILY 실행이 FAILED이면 오래된 성공 실행으로 주간 근거를 대체하지 않는다")
    void latestFailedDailyExecutionBlocksWeeklyEvidence() {
        LocalDate weekStart = LocalDate.parse("2026-07-20");
        seedRemainingWeeklyExecutions(weekStart);
        UUID failedId = UUID.nameUUIDFromBytes("failed-2026-07-21".getBytes(StandardCharsets.UTF_8));
        jdbcClient.sql("""
                    INSERT INTO rtms_collection_execution (
                        execution_id, collection_mode, scope_type, run_date, state,
                        planned_work_unit_count, started_at, completed_at, failure_reason
                    ) VALUES (
                        :executionId, 'DAILY', 'NATIONWIDE', DATE '2026-07-21', 'FAILED',
                        1, TIMESTAMPTZ '2026-07-21 02:00:00Z', TIMESTAMPTZ '2026-07-21 02:01:00Z',
                        'provider unavailable'
                    )
                    """).param("executionId", failedId).update();

        assertThat(buildRepository.findLatestDailyNationwideForWeek(weekStart))
                .extracting(MarketInsightSourceExecution::runDate)
                .doesNotContain(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("시도 기준정보가 17개가 아니면 어느 scope도 발행하지 않는다")
    void rejectsPublicationWhenSidoCatalogIsIncomplete() {
        jdbcClient.sql("DELETE FROM region WHERE id = 2001").update();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        assertThatThrownBy(() -> buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:04:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("17");
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_insight_snapshot")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("당일 최신 DAILY 실행이 진행 중이면 오래된 성공 실행으로 대체하지 않는다")
    void latestRunningDailyExecutionDoesNotFallBackToOlderSuccess() {
        UUID runningExecutionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_execution (
                        execution_id, collection_mode, scope_type, run_date, state,
                        planned_work_unit_count, started_at
                    ) VALUES (
                        :executionId, 'DAILY', 'NATIONWIDE', :runDate, 'RUNNING',
                        1, TIMESTAMPTZ '2026-07-22 01:00:00Z'
                    )
                    """)
                .param("executionId", runningExecutionId)
                .param("runDate", RUN_DATE)
                .update();
        jdbcClient.sql("""
                    INSERT INTO rtms_collection_work_unit (
                        execution_id, lawd_cd, deal_ymd, state, started_at
                    ) VALUES (
                        :executionId, '26440', '202607', 'RUNNING',
                        TIMESTAMPTZ '2026-07-22 01:00:00Z'
                    )
                    """).param("executionId", runningExecutionId).update();

        assertThat(buildRepository.findLatestDailyNationwide(RUN_DATE))
                .get()
                .extracting(MarketInsightSourceExecution::executionId)
                .isEqualTo(runningExecutionId);
    }

    @Test
    @DisplayName("coverage gate 거부는 source 유무와 함께 REJECTED evidence로 저장된다")
    void storesRejectedBuildEvidenceWithAndWithoutSource() {
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID withSource = buildRepository.rejectDailyNationwide(
                RUN_DATE,
                source,
                MarketInsightRejectionReason.INCOMPLETE_WORKSET,
                Instant.parse("2026-07-22T00:07:00Z"));
        UUID withoutSource = buildRepository.rejectDailyNationwide(
                RUN_DATE.plusDays(1),
                null,
                MarketInsightRejectionReason.INCOMPLETE_WORKSET,
                Instant.parse("2026-07-23T00:07:00Z"));

        assertThat(withSource).isNotEqualTo(withoutSource);
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_insight_snapshot WHERE build_status = 'REJECTED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(buildRepository.findLatestDailyNationwide(RUN_DATE.plusDays(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("시도 snapshot은 전국 top 50을 필터링하지 않고 scope 후보 전체를 독립 rank한다")
    void ranksSidoCandidatesIndependentlyFromNationwideTopFifty() {
        seedBusanCandidatesAboveNationwideCutoff();
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));

        var nationwide = readRepository
                .findLatestDaily(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 50)
                .orElseThrow();
        var seoul = readRepository
                .findLatestDaily(MarketInsightScopeType.SIDO, "11", RUN_DATE, 10)
                .orElseThrow();

        assertThat(nationwide.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.DAILY_HIGHEST_DEAL)
                .hasSize(50)
                .allSatisfy(item -> assertThat(item.sidoName()).isEqualTo("Busan"));
        assertThat(seoul.items())
                .filteredOn(item -> item.metricType() == MarketInsightMetricType.DAILY_HIGHEST_DEAL)
                .first()
                .satisfies(item -> {
                    assertThat(item.rank()).isEqualTo(1);
                    assertThat(item.dealAmount()).isEqualTo(130000L);
                    assertThat(item.sidoName()).isEqualTo("Seoul");
                });
    }

    @Test
    @DisplayName("property runtime은 insight evidence를 읽고 생성·갱신하되 삭제할 수 없다")
    void propertyRuntimeOwnsMinimumInsightPrivileges() {
        assertThat(hasTablePrivilege("rtms_collection_execution", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("rtms_collection_work_unit", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("market_insight_snapshot", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("market_insight_trade_item", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("market_insight_snapshot_execution", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("market_insight_snapshot", "DELETE")).isFalse();
        assertThat(hasTablePrivilege("market_insight_trade_item", "DELETE")).isFalse();
        assertThat(hasTablePrivilege("market_insight_snapshot_execution", "DELETE"))
                .isFalse();
    }

    private void seedRemainingSidoRegions() {
        jdbcClient.sql("""
                    INSERT INTO region (id, code, name, region_type) VALUES
                        (2001, '26', 'Busan', 'si-do'),
                        (2002, '27', 'Daegu', 'si-do'),
                        (2003, '28', 'Incheon', 'si-do'),
                        (2004, '29', 'Gwangju', 'si-do'),
                        (2005, '30', 'Daejeon', 'si-do'),
                        (2006, '31', 'Ulsan', 'si-do'),
                        (2007, '36', 'Sejong', 'si-do'),
                        (2008, '41', 'Gyeonggi', 'si-do'),
                        (2009, '42', 'Gangwon', 'si-do'),
                        (2010, '43', 'Chungbuk', 'si-do'),
                        (2011, '44', 'Chungnam', 'si-do'),
                        (2012, '45', 'Jeonbuk', 'si-do'),
                        (2013, '46', 'Jeonnam', 'si-do'),
                        (2014, '47', 'Gyeongbuk', 'si-do'),
                        (2015, '48', 'Gyeongnam', 'si-do'),
                        (2016, '50', 'Jeju', 'si-do')
                    """).update();
    }

    private void seedRemainingWeeklyExecutions(LocalDate weekStart) {
        for (int day = 0; day < 7; day++) {
            LocalDate runDate = weekStart.plusDays(day);
            if (runDate.equals(RUN_DATE)) continue;
            UUID executionId = UUID.nameUUIDFromBytes(runDate.toString().getBytes(StandardCharsets.UTF_8));
            OffsetDateTime startedAt = OffsetDateTime.of(runDate, java.time.LocalTime.MIDNIGHT, ZoneOffset.UTC);
            OffsetDateTime completedAt = startedAt.plusMinutes(3);
            jdbcClient
                    .sql("""
                        INSERT INTO rtms_collection_execution (
                            execution_id, collection_mode, scope_type, run_date, state,
                            planned_work_unit_count, started_at, completed_at
                        ) VALUES (
                            :executionId, 'DAILY', 'NATIONWIDE', :runDate, 'COMPLETED',
                            1, :startedAt, :completedAt
                        )
                        """)
                    .param("executionId", executionId)
                    .param("runDate", runDate)
                    .param("startedAt", startedAt)
                    .param("completedAt", completedAt)
                    .update();
            long ingestRunId = jdbcClient
                    .sql("""
                        INSERT INTO rtms_ingest_run (
                            lawd_cd, deal_ymd, status, page_count, read_count, raw_saved_count,
                            normalized_inserted_count, duplicate_skipped_count, canceled_skipped_count,
                            match_failed_count, parse_failed_count, started_at, completed_at,
                            execution_correlation_id
                        ) VALUES (
                            '11680', '202607', 'COMPLETED', 0, 0, 0,
                            0, 0, 0, 0, 0, :startedAt, :completedAt, :executionId
                        ) RETURNING id
                        """)
                    .param("executionId", executionId)
                    .param("startedAt", startedAt)
                    .param("completedAt", completedAt)
                    .query(Long.class)
                    .single();
            jdbcClient
                    .sql("""
                        INSERT INTO rtms_collection_work_unit (
                            execution_id, lawd_cd, deal_ymd, state, rtms_ingest_run_id,
                            started_at, completed_at
                        ) VALUES (
                            :executionId, '11680', '202607', 'COMPLETED', :ingestRunId,
                            :startedAt, :completedAt
                        )
                        """)
                    .param("executionId", executionId)
                    .param("ingestRunId", ingestRunId)
                    .param("startedAt", startedAt)
                    .param("completedAt", completedAt)
                    .update();
        }
    }

    private boolean hasTablePrivilege(String table, String privileges) {
        return jdbcClient
                .sql("SELECT has_table_privilege(:role, :table, :privileges)")
                .param("role", PROPERTY_RUNTIME_ROLE)
                .param("table", table)
                .param("privileges", privileges)
                .query(Boolean.class)
                .single();
    }

    private void seedExactAreaAndCancellationEvidence() {
        jdbcClient.sql("""
                    INSERT INTO raw_trade_ingest (
                        id, source, source_key, lawd_cd, deal_ymd, page_no,
                        payload, payload_hash, status, processed_at, execution_correlation_id
                    ) VALUES
                        (91001, 'RTMS', 'area-8499-prior-low', '11680', '202511', 1, '{}', 'h-91001', 'NORMALIZED', TIMESTAMPTZ '2025-11-02 00:00:00Z', NULL),
                        (91002, 'RTMS', 'area-8499-prior-high', '11680', '202511', 1, '{}', 'h-91002', 'NORMALIZED', TIMESTAMPTZ '2025-11-02 00:01:00Z', NULL),
                        (91003, 'RTMS', 'area-8498-prior', '11680', '202511', 1, '{}', 'h-91003', 'NORMALIZED', TIMESTAMPTZ '2025-11-02 00:02:00Z', NULL),
                        (91004, 'RTMS', 'area-8499-current', '11680', '202512', 1, '{}', 'h-91004', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:10Z', :executionId),
                        (91005, 'RTMS', 'area-7400-prior-low', '11680', '202511', 1, '{}', 'h-91005', 'NORMALIZED', TIMESTAMPTZ '2025-11-06 00:00:00Z', NULL),
                        (91006, 'RTMS', 'area-7400-prior-high', '11680', '202511', 1, '{}', 'h-91006', 'NORMALIZED', TIMESTAMPTZ '2025-11-06 00:01:00Z', NULL),
                        (91007, 'RTMS', 'area-7400-current', '11680', '202512', 1, '{}', 'h-91007', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:20Z', :executionId),
                        (91008, 'RTMS', 'area-5999-prior', '11680', '202511', 1, '{}', 'h-91008', 'NORMALIZED', TIMESTAMPTZ '2025-11-07 00:00:00Z', NULL),
                        (91009, 'RTMS', 'area-5999-current', '11680', '202512', 1, '{}', 'h-91009', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:30Z', :executionId),
                        (91010, 'RTMS', 'area-8499-recent-prior', '11680', '202605', 1, '{}', 'h-91010', 'NORMALIZED', TIMESTAMPTZ '2026-05-21 00:00:00Z', NULL),
                        (91011, 'RTMS', 'old-contract-current-ingest', '11680', '202605', 1, '{}', 'h-91011', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:31Z', :executionId),
                        (91012, 'RTMS', 'area-6800-old-prior', '11680', '202512', 1, '{}', 'h-91012', 'NORMALIZED', TIMESTAMPTZ '2025-12-02 00:00:00Z', NULL),
                        (91013, 'RTMS', 'area-6800-current', '11680', '202607', 1, '{}', 'h-91013', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:32Z', :executionId),
                        (92001, 'RTMS', 'cancel-source-key', '11680', '202512', 1, '{}', 'h-92001', 'NORMALIZED', TIMESTAMPTZ '2025-12-11 00:00:00Z', NULL),
                        (92002, 'RTMS', 'cancel-source-key', '11680', '202607', 1, '{}', 'h-92002', 'CANCELED', TIMESTAMPTZ '2026-07-22 00:02:40Z', :executionId),
                        (92003, 'RTMS', 'cancel-source-key', '11680', '202607', 1, '{}', 'h-92003', 'CANCELED', TIMESTAMPTZ '2026-07-22 00:02:50Z', :executionId)
                    """).param("executionId", EXECUTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO trade (
                        id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
                        source, source_key, complex_pk, apt_seq, raw_ingest_id, deleted_at
                    ) VALUES
                        (9101, 501, DATE '2025-11-01', 100000, 10, 84.99, '101', 'RTMS', 'area-8499-prior-low', 'COMPLEX-PK-501', 'APT-501', 91001, NULL),
                        (9102, 501, DATE '2025-11-01', 120000, 11, 84.99, '101', 'RTMS', 'area-8499-prior-high', 'COMPLEX-PK-501', 'APT-501', 91002, NULL),
                        (9103, 501, DATE '2025-11-01', 200000, 12, 84.98, '101', 'RTMS', 'area-8498-prior', 'COMPLEX-PK-501', 'APT-501', 91003, NULL),
                        (9104, 501, DATE '2026-07-20', 130000, 13, 84.99, '101', 'RTMS', 'area-8499-current', 'COMPLEX-PK-501', 'APT-501', 91004, NULL),
                        (9105, 501, DATE '2026-05-20', 200000, 10, 74.00, '101', 'RTMS', 'area-7400-prior-low', 'COMPLEX-PK-501', 'APT-501', 91005, NULL),
                        (9106, 501, DATE '2026-05-20', 220000, 11, 74.00, '101', 'RTMS', 'area-7400-prior-high', 'COMPLEX-PK-501', 'APT-501', 91006, NULL),
                        (9107, 501, DATE '2026-07-20', 180000, 12, 74.00, '101', 'RTMS', 'area-7400-current', 'COMPLEX-PK-501', 'APT-501', 91007, NULL),
                        (9108, 501, DATE '2026-05-20', 100000, 10, 59.99, '101', 'RTMS', 'area-5999-prior', 'COMPLEX-PK-501', 'APT-501', 91008, NULL),
                        (9109, 501, DATE '2026-07-20', 100000, 11, 59.99, '101', 'RTMS', 'area-5999-current', 'COMPLEX-PK-501', 'APT-501', 91009, NULL),
                        (9110, 501, DATE '2026-05-20', 110000, 12, 84.99, '101', 'RTMS', 'area-8499-recent-prior', 'COMPLEX-PK-501', 'APT-501', 91010, NULL),
                        (9111, 501, DATE '2026-05-01', 999999, 14, 72.00, '101', 'RTMS', 'old-contract-current-ingest', 'COMPLEX-PK-501', 'APT-501', 91011, NULL),
                        (9112, 501, DATE '2025-12-01', 90000, 9, 68.00, '101', 'RTMS', 'area-6800-old-prior', 'COMPLEX-PK-501', 'APT-501', 91012, NULL),
                        (9113, 501, DATE '2026-07-18', 100000, 10, 68.00, '101', 'RTMS', 'area-6800-current', 'COMPLEX-PK-501', 'APT-501', 91013, NULL),
                        (9201, 501, DATE '2025-12-10', 150000, 12, 84.50, '101', 'RTMS', 'cancel-source-key', 'COMPLEX-PK-501', 'APT-501', 92001, TIMESTAMPTZ '2026-07-22 00:02:45Z')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO trade_source_key_registry (
                        source, source_key, raw_ingest_id, trade_id, trade_deal_date
                    ) VALUES
                        ('RTMS', 'area-8499-current', 91004, 9104, DATE '2026-07-20'),
                        ('RTMS', 'area-7400-current', 91007, 9107, DATE '2026-07-20'),
                        ('RTMS', 'area-5999-current', 91009, 9109, DATE '2026-07-20'),
                        ('RTMS', 'old-contract-current-ingest', 91011, 9111, DATE '2026-05-01'),
                        ('RTMS', 'area-6800-current', 91013, 9113, DATE '2026-07-18'),
                        ('RTMS', 'cancel-source-key', 92001, 9201, DATE '2025-12-10')
                    """).update();
        jdbcClient.sql("""
                    UPDATE raw_trade_ingest
                    SET registration_date_raw = '26.07.22',
                        registration_date = DATE '2026-07-22',
                        cancellation_date_raw = CASE WHEN status = 'CANCELED' THEN '26.07.22' END,
                        cancellation_date = CASE WHEN status = 'CANCELED' THEN DATE '2026-07-22' END
                    WHERE id IN (91004, 91007, 91009, 91011, 91013, 92002, 92003)
                    """).update();
    }

    private void seedBusanCandidatesAboveNationwideCutoff() {
        jdbcClient.sql("""
                    INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
                    VALUES (22, 2001, '26110', 'Jung-gu', 'si-gun-gu', 35.1062, 129.0324),
                           (222, 22, '26110101', 'Jungang-dong', 'eup-myeon-dong', 35.1038, 129.0364)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                    SELECT 2000 + value,
                           222,
                           '26110101001' || lpad(value::text, 8, '0'),
                           'Busan sample ' || value,
                           35.10 + value / 100000.0,
                           129.03 + value / 100000.0
                    FROM generate_series(1, 51) value
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex (
                        id, parcel_id, region_id, complex_pk, apt_seq, name, trade_name
                    )
                    SELECT 6000 + value,
                           2000 + value,
                           222,
                           'BUSAN-PK-' || value,
                           'BUSAN-APT-' || value,
                           'Busan complex ' || value,
                           'Busan trade ' || value
                    FROM generate_series(1, 51) value
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO raw_trade_ingest (
                        id, source, source_key, lawd_cd, deal_ymd, page_no,
                        payload, payload_hash, status, processed_at, execution_correlation_id
                    )
                    SELECT 93000 + value,
                           'RTMS',
                           'busan-current-' || value,
                           '26110',
                           '202512',
                           1,
                           '{}',
                           'busan-hash-' || value,
                           'NORMALIZED',
                           TIMESTAMPTZ '2026-07-22 00:02:00Z' + value * INTERVAL '1 second',
                           :executionId
                    FROM generate_series(1, 51) value
                    """).param("executionId", EXECUTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO trade (
                        id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
                        source, source_key, complex_pk, apt_seq, raw_ingest_id
                    )
                    SELECT 9300 + value,
                           6000 + value,
                           DATE '2025-12-20',
                           500000 + value,
                           10,
                           84.99,
                           '101',
                           'RTMS',
                           'busan-current-' || value,
                           'BUSAN-PK-' || value,
                           'BUSAN-APT-' || value,
                           93000 + value
                    FROM generate_series(1, 51) value
                    """).update();
    }

    private void seedRollingQualityEvidence() {
        jdbcClient.sql("""
                    INSERT INTO raw_trade_ingest (
                        id, source, source_key, lawd_cd, deal_ymd, page_no,
                        payload, payload_hash, status, processed_at, execution_correlation_id,
                        registration_date_raw, registration_date,
                        cancellation_date_raw, cancellation_date
                    ) VALUES
                        (94001, 'RTMS', 'missing-registration', '11680', '202607', 1,
                         '{}', 'h-94001', 'DUPLICATE', TIMESTAMPTZ '2026-07-22 00:02:10Z',
                         :executionId, NULL, NULL, NULL, NULL),
                        (94002, 'RTMS', 'invalid-registration', '11680', '202607', 1,
                         '{}', 'h-94002', 'DUPLICATE', TIMESTAMPTZ '2026-07-22 00:02:20Z',
                         :executionId, '26.02.30', NULL, NULL, NULL),
                        (94003, 'RTMS', 'missing-cancellation', '11680', '202607', 1,
                         '{}', 'h-94003', 'CANCELED', TIMESTAMPTZ '2026-07-22 00:02:30Z',
                         :executionId, '26.07.01', DATE '2026-07-01', NULL, NULL),
                        (94004, 'RTMS', 'invalid-cancellation', '11680', '202607', 1,
                         '{}', 'h-94004', 'CANCELED', TIMESTAMPTZ '2026-07-22 00:02:40Z',
                         :executionId, '26.07.01', DATE '2026-07-01', 'bad-date', NULL),
                        (94005, 'RTMS', 'sample-rtms-20251215', '11680', '202607', 1,
                         '{}', 'h-94005', 'DUPLICATE', TIMESTAMPTZ '2026-07-22 00:02:50Z',
                         :executionId, '26.07.22', DATE '2026-07-22', NULL, NULL),
                        (94006, 'RTMS', 'fallback-missing-registration', '11680', '202607', 1,
                         '{}', 'h-94006', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:51Z',
                         :executionId, NULL, NULL, NULL, NULL),
                        (94007, 'RTMS', 'fallback-invalid-registration', '11680', '202607', 1,
                         '{}', 'h-94007', 'NORMALIZED', TIMESTAMPTZ '2026-07-22 00:02:52Z',
                         :executionId, '26.02.30', NULL, NULL, NULL),
                        (94008, 'RTMS', 'canceled-missing-registration', '11680', '202607', 1,
                         '{}', 'h-94008', 'CANCELED', TIMESTAMPTZ '2026-07-22 00:02:53Z',
                         :executionId, NULL, NULL, '26.07.22', DATE '2026-07-22')
                    """).param("executionId", EXECUTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO trade (
                        id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
                        source, source_key, complex_pk, apt_seq, raw_ingest_id, deleted_at
                    ) VALUES
                        (9406, 501, DATE '2026-07-20', 300000, 10, 84.99, '101',
                         'RTMS', 'fallback-missing-registration', 'COMPLEX-PK-501', 'APT-501',
                         94006, NULL),
                        (9407, 501, DATE '2026-07-21', 310000, 11, 84.99, '101',
                         'RTMS', 'fallback-invalid-registration', 'COMPLEX-PK-501', 'APT-501',
                         94007, NULL),
                        (9408, 501, DATE '2026-07-22', 320000, 12, 84.99, '101',
                         'RTMS', 'canceled-missing-registration', 'COMPLEX-PK-501', 'APT-501',
                         94008, TIMESTAMPTZ '2026-07-22 00:02:45Z')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO trade_source_key_registry (
                        source, source_key, raw_ingest_id, trade_id, trade_deal_date
                    ) VALUES
                        ('RTMS', 'missing-registration', 94001, 9001, DATE '2025-12-01'),
                        ('RTMS', 'invalid-registration', 94002, 9001, DATE '2025-12-01'),
                        ('RTMS', 'missing-cancellation', 94003, 9002, DATE '2025-12-15'),
                        ('RTMS', 'invalid-cancellation', 94004, 9002, DATE '2025-12-15'),
                        ('RTMS', 'fallback-missing-registration', 94006, 9406, DATE '2026-07-20'),
                        ('RTMS', 'fallback-invalid-registration', 94007, 9407, DATE '2026-07-21'),
                        ('RTMS', 'canceled-missing-registration', 94008, 9408, DATE '2026-07-22')
                    """).update();
    }

    private UUID seedNewerCompletedExecution(String startedAtText) {
        UUID executionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174101");
        OffsetDateTime startedAt = offset(startedAtText);
        OffsetDateTime completedAt = startedAt.plusMinutes(3);
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_execution (
                        execution_id, collection_mode, scope_type, run_date, state,
                        planned_work_unit_count, started_at, completed_at
                    ) VALUES (
                        :executionId, 'DAILY', 'NATIONWIDE', :runDate, 'COMPLETED',
                        1, :startedAt, :completedAt
                    )
                    """)
                .param("executionId", executionId)
                .param("runDate", RUN_DATE)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .update();
        long ingestRunId = jdbcClient
                .sql("""
                    INSERT INTO rtms_ingest_run (
                        lawd_cd, deal_ymd, status, page_count, read_count, raw_saved_count,
                        normalized_inserted_count, duplicate_skipped_count, canceled_skipped_count,
                        match_failed_count, parse_failed_count, started_at, completed_at,
                        execution_correlation_id
                    ) VALUES (
                        '11680', '202607', 'COMPLETED', 0, 0, 0, 0, 0, 0, 0, 0,
                        :startedAt, :completedAt, :executionId
                    ) RETURNING id
                    """)
                .param("executionId", executionId)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_work_unit (
                        execution_id, lawd_cd, deal_ymd, state, rtms_ingest_run_id,
                        started_at, completed_at
                    ) VALUES (
                        :executionId, '11680', '202607', 'COMPLETED', :ingestRunId,
                        :startedAt, :completedAt
                    )
                    """)
                .param("executionId", executionId)
                .param("ingestRunId", ingestRunId)
                .param("startedAt", startedAt)
                .param("completedAt", completedAt)
                .update();
        return executionId;
    }

    private static OffsetDateTime offset(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }
}
