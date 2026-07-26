package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsExecutionFailureCorrectionMigrationTest extends JdbcMigrationTestSupport {

    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174530");

    @Test
    @DisplayName("V28은 provider quota 원인을 budget skip보다 우선해 보정하고 이전 값을 보존한다")
    void reconcilesProviderFailureHiddenByRemainingBudgetSkips() {
        flyway(MigrationVersion.fromVersion("27")).clean();
        flyway(MigrationVersion.fromVersion("27")).migrate();
        seedExecutionWithHiddenProviderFailure();

        flyway(null).migrate();

        assertThat(jdbcClient
                        .sql("""
                            SELECT failure_kind
                            FROM market_news_collection_execution
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("DAILY_QUOTA");
        assertThat(jdbcClient
                        .sql("""
                            SELECT old_failure_kind || '|' || new_failure_kind || '|' || correction_reason
                            FROM market_news_execution_failure_correction
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("DAILY_CALL_BUDGET|DAILY_QUOTA|PROVIDER_FAILURE_PRECEDENCE");
    }

    private void seedExecutionWithHiddenProviderFailure() {
        jdbcClient.sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget, call_count,
                        planned_work_unit_count, failed_work_unit_count,
                        skipped_budget_work_unit_count, started_at, completed_at, failure_kind
                    ) VALUES (
                        :executionId, 'NEWS-V28-CORRECTION', 'MAJOR_COMPLEX', 'NEWS_V3',
                        now(), now() - interval '2 hours', 'FAILED', 4000, 1,
                        2, 1, 1, now() - interval '1 hour', now(), 'DAILY_CALL_BUDGET'
                    )
                    """).param("executionId", EXECUTION_ID).update();
        insertWorkUnit("123e4567-e89b-12d3-a456-426614174531", 1, "FAILED", "DAILY_QUOTA");
        insertWorkUnit("123e4567-e89b-12d3-a456-426614174532", 2, "SKIPPED_BUDGET", "DAILY_CALL_BUDGET");
    }

    private void insertWorkUnit(String id, int order, String state, String failureKind) {
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_work_unit (
                        work_unit_id, execution_id, unit_order, scope_kind, scope_type,
                        category, query_text, cutoff_at, state, failure_kind,
                        started_at, completed_at
                    ) VALUES (
                        CAST(:workUnitId AS uuid), :executionId, :unitOrder,
                        'NATIONAL_CATEGORY', 'NATIONWIDE', 'POLICY', '부동산 정책 아파트',
                        now() - interval '2 hours', :state, :failureKind,
                        now() - interval '1 hour', now()
                    )
                    """)
                .param("workUnitId", id)
                .param("executionId", EXECUTION_ID)
                .param("unitOrder", order)
                .param("state", state)
                .param("failureKind", failureKind)
                .update();
    }
}
