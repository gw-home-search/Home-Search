package com.home.infrastructure.persistence.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.insight.collection.RtmsCollectionExecutionPlan;
import com.home.application.insight.collection.RtmsCollectionWorkUnitPlan;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsCollectionExecutionRepositoryTest extends JdbcMigrationTestSupport {

    private JdbcRtmsCollectionExecutionRepository repository;

    @BeforeEach
    void migrate() {
        flyway(null).clean();
        flyway(null).migrate();
        repository = new JdbcRtmsCollectionExecutionRepository(jdbcClient);
    }

    @Test
    @DisplayName("같은 requestId와 workset 재시작은 execution/work unit을 중복 생성하지 않는다")
    void sameRequestIdRestartReusesExactPlan() {
        ExecutionCorrelationId executionId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174007");
        RtmsCollectionExecutionPlan plan = new RtmsCollectionExecutionPlan(
                executionId,
                RtmsCollectionMode.DAILY,
                RtmsCollectionScopeType.NATIONWIDE,
                LocalDate.parse("2026-07-22"),
                List.of(
                        new RtmsCollectionWorkUnitPlan("11680", "202607"),
                        new RtmsCollectionWorkUnitPlan("11710", "202607")));

        transactionTemplate.executeWithoutResult(
                ignored -> repository.savePlan(plan, Instant.parse("2026-07-22T00:00:00Z")));
        transactionTemplate.executeWithoutResult(
                ignored -> repository.savePlan(plan, Instant.parse("2026-07-22T00:01:00Z")));

        assertThat(count("SELECT count(*) FROM rtms_collection_execution")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rtms_collection_work_unit")).isEqualTo(2);
    }

    @Test
    @DisplayName("work unit terminal 결과는 ingest run과 연결되고 coverage count를 만든다")
    void terminalWorkUnitLinksIngestRunAndCreatesCoverage() {
        ExecutionCorrelationId executionId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174008");
        RtmsCollectionExecutionPlan plan = new RtmsCollectionExecutionPlan(
                executionId,
                RtmsCollectionMode.DAILY,
                RtmsCollectionScopeType.NATIONWIDE,
                LocalDate.parse("2026-07-22"),
                List.of(new RtmsCollectionWorkUnitPlan("11680", "202607")));
        transactionTemplate.executeWithoutResult(ignored -> {
            repository.savePlan(plan, Instant.parse("2026-07-22T00:00:00Z"));
            repository.markRunning(executionId, "11680", "202607", Instant.parse("2026-07-22T00:01:00Z"));
        });
        long runId = jdbcClient
                .sql("""
                    INSERT INTO rtms_ingest_run (
                        lawd_cd, deal_ymd, status, page_count, read_count, raw_saved_count,
                        normalized_inserted_count, duplicate_skipped_count, canceled_skipped_count,
                        match_failed_count, parse_failed_count, started_at, completed_at,
                        execution_correlation_id
                    ) VALUES (
                        '11680', '202607', 'COMPLETED', 1, 0, 0, 0, 0, 0, 0, 0,
                        :startedAt, :completedAt, :executionId
                    ) RETURNING id
                    """)
                .param("startedAt", OffsetDateTime.ofInstant(Instant.parse("2026-07-22T00:01:00Z"), ZoneOffset.UTC))
                .param("completedAt", OffsetDateTime.ofInstant(Instant.parse("2026-07-22T00:02:00Z"), ZoneOffset.UTC))
                .param("executionId", executionId.value())
                .query(Long.class)
                .single();

        var coverage = transactionTemplate.execute(ignored -> {
            repository.markTerminal(
                    executionId,
                    "11680",
                    "202607",
                    RtmsCollectionWorkUnitState.COMPLETED,
                    runId,
                    Instant.parse("2026-07-22T00:02:00Z"));
            return repository.finish(executionId, Instant.parse("2026-07-22T00:03:00Z"));
        });

        assertThat(coverage.plannedCount()).isEqualTo(1);
        assertThat(coverage.completedCount()).isEqualTo(1);
        assertThat(jdbcClient
                        .sql("SELECT state FROM rtms_collection_execution")
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
