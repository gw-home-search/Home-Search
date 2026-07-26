package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsExecutionAggregateCorrectionMigrationTest extends JdbcMigrationTestSupport {

    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174527");

    @Test
    @DisplayName("V27은 과거 뉴스 execution 파생 집계를 work unit 근거로 보정하고 이전 값을 보존한다")
    void reconcilesDerivedExecutionAggregatesWithAuditEvidence() {
        flyway(MigrationVersion.fromVersion("26")).clean();
        flyway(MigrationVersion.fromVersion("26")).migrate();
        seedMismatchedExecution();

        flyway(null).migrate();

        assertThat(jdbcClient
                        .sql("""
                            SELECT completed_work_unit_count || '|' ||
                                   truncated_work_unit_count || '|' ||
                                   failed_work_unit_count || '|' ||
                                   skipped_budget_work_unit_count || '|' ||
                                   raw_item_count || '|' || bootstrap_truncated
                            FROM market_news_collection_execution
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("1|1|0|0|0|true");
        assertThat(jdbcClient
                        .sql("""
                            SELECT old_completed_count || '|' || new_completed_count || '|' ||
                                   old_truncated_count || '|' || new_truncated_count || '|' ||
                                   old_raw_item_count || '|' || new_raw_item_count || '|' || correction_reason
                            FROM market_news_execution_aggregate_correction
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("99|1|88|1|42|0|DERIVED_WORK_UNIT_RECONCILIATION");
    }

    private void seedMismatchedExecution() {
        jdbcClient.sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget, call_count,
                        planned_work_unit_count, completed_work_unit_count,
                        truncated_work_unit_count, raw_item_count, bootstrap_truncated,
                        started_at, completed_at
                    ) VALUES (
                        :executionId, 'NEWS-V27-CORRECTION', 'BOOTSTRAP', 'NEWS_V1',
                        now(), now() - interval '30 days', 'PARTIAL', 4000, 2,
                        2, 99, 88, 42, false, now() - interval '1 hour', now()
                    )
                    """).param("executionId", EXECUTION_ID).update();
        insertWorkUnit("123e4567-e89b-12d3-a456-426614174528", 1, "COMPLETED", true);
        insertWorkUnit("123e4567-e89b-12d3-a456-426614174529", 2, "TRUNCATED", false);
    }

    private void insertWorkUnit(String id, int order, String state, boolean cutoffReached) {
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_work_unit (
                        work_unit_id, execution_id, unit_order, scope_kind, scope_type,
                        category, query_text, cutoff_at, cutoff_reached, state,
                        started_at, completed_at
                    ) VALUES (
                        CAST(:workUnitId AS uuid), :executionId, :unitOrder,
                        'NATIONAL_CATEGORY', 'NATIONWIDE', 'POLICY', '부동산 정책',
                        now() - interval '30 days', :cutoffReached, :state,
                        now() - interval '1 hour', now()
                    )
                    """)
                .param("workUnitId", id)
                .param("executionId", EXECUTION_ID)
                .param("unitOrder", order)
                .param("cutoffReached", cutoffReached)
                .param("state", state)
                .update();
    }
}
