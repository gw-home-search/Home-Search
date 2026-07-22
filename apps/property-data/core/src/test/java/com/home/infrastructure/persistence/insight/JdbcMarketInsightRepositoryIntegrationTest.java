package com.home.infrastructure.persistence.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightRejectionReason;
import com.home.domain.insight.MarketInsightScopeType;
import com.home.domain.insight.MarketInsightTradeStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
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
                        processed_at = CASE id
                            WHEN 90001 THEN TIMESTAMPTZ '2026-07-22 00:01:00Z'
                            ELSE TIMESTAMPTZ '2026-07-22 00:02:00Z'
                        END
                    """).param("executionId", EXECUTION_ID).update();
    }

    @Test
    @DisplayName("완결된 DAILY 실행은 idempotent snapshot으로 발행되고 public read model로 조회된다")
    void publishesAndReadsDailySnapshotIdempotently() {
        MarketInsightSourceExecution source =
                buildRepository.findLatestDailyNationwide(RUN_DATE).orElseThrow();

        UUID snapshotId = buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:04:00Z"));
        UUID repeatedSnapshotId = buildRepository.publishDailyNationwide(source, Instant.parse("2026-07-22T00:05:00Z"));

        assertThat(repeatedSnapshotId).isEqualTo(snapshotId);
        assertThat(source.coverage().completedCount()).isEqualTo(1);
        assertThat(source.completedAt()).isEqualTo(Instant.parse("2026-07-22T00:03:00Z"));

        jdbcClient.sql("""
                    UPDATE market_insight_trade_item
                    SET previous_amount = 120000,
                        previous_deal_date = DATE '2025-11-01',
                        delta_amount = 5000,
                        delta_rate = 4.166667,
                        current_count = 2,
                        previous_count = 1,
                        comparison_sample_count = 3
                    WHERE snapshot_id = :snapshotId
                    """).param("snapshotId", snapshotId).update();
        jdbcClient
                .sql("UPDATE trade SET deleted_at = TIMESTAMPTZ '2026-07-22 00:06:00Z' WHERE id = 9002")
                .update();

        var snapshot = readRepository
                .findLatestDaily(MarketInsightScopeType.NATIONWIDE, null, RUN_DATE, 1)
                .orElseThrow();

        assertThat(snapshot.snapshotId()).isEqualTo(snapshotId);
        assertThat(snapshot.scopeType()).isEqualTo(MarketInsightScopeType.NATIONWIDE);
        assertThat(snapshot.regionCode()).isNull();
        assertThat(snapshot.items()).hasSize(2);
        assertThat(snapshot.items()).allSatisfy(item -> {
            assertThat(item.rank()).isEqualTo(1);
            assertThat(item.complexName()).isEqualTo("Sample trade name");
            assertThat(item.sidoName()).isEqualTo("Seoul");
            assertThat(item.sigunguName()).isEqualTo("Gangnam-gu");
            assertThat(item.previousAmount()).isEqualTo(120000L);
            assertThat(item.currentCount()).isEqualTo(2);
        });
        assertThat(snapshot.items()).extracting(item -> item.tradeStatus()).contains(MarketInsightTradeStatus.CANCELED);
        assertThat(readRepository.findLatestDaily(MarketInsightScopeType.SIDO, "11", RUN_DATE, 10))
                .isEmpty();
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
        assertThat(hasTablePrivilege("market_insight_snapshot", "DELETE")).isFalse();
        assertThat(hasTablePrivilege("market_insight_trade_item", "DELETE")).isFalse();
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

    private static OffsetDateTime offset(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }
}
