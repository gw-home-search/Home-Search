package com.home.infrastructure.persistence.insight;

import com.home.application.insight.collection.RtmsCollectionExecutionPlan;
import com.home.application.insight.collection.RtmsCollectionExecutionRepository;
import com.home.application.insight.collection.RtmsCollectionWorkUnitPlan;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.RtmsCollectionExecutionState;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRtmsCollectionExecutionRepository implements RtmsCollectionExecutionRepository {

    private final JdbcClient jdbcClient;

    public JdbcRtmsCollectionExecutionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public void savePlan(RtmsCollectionExecutionPlan plan, Instant startedAt) {
        jdbcClient
                .sql("""
                    INSERT INTO rtms_collection_execution (
                        execution_id, collection_mode, scope_type, run_date, state,
                        planned_work_unit_count, started_at
                    ) VALUES (
                        :executionId, :collectionMode, :scopeType, :runDate, 'PLANNED',
                        :plannedCount, :startedAt
                    )
                    ON CONFLICT (execution_id) DO NOTHING
                    """)
                .param("executionId", plan.executionId().value())
                .param("collectionMode", plan.collectionMode().name())
                .param("scopeType", plan.scopeType().name())
                .param("runDate", plan.runDate())
                .param("plannedCount", plan.workUnits().size())
                .param("startedAt", offset(startedAt))
                .update();

        ExistingPlan existing = jdbcClient
                .sql("""
                    SELECT collection_mode, scope_type, run_date, planned_work_unit_count
                    FROM rtms_collection_execution
                    WHERE execution_id = :executionId
                    """)
                .param("executionId", plan.executionId().value())
                .query((rs, rowNum) -> new ExistingPlan(
                        RtmsCollectionMode.valueOf(rs.getString("collection_mode")),
                        RtmsCollectionScopeType.valueOf(rs.getString("scope_type")),
                        rs.getObject("run_date", java.time.LocalDate.class),
                        rs.getInt("planned_work_unit_count")))
                .single();
        if (!existing.matches(plan)) {
            throw new IllegalStateException("requestId is already associated with a different collection plan");
        }

        for (RtmsCollectionWorkUnitPlan unit : plan.workUnits()) {
            jdbcClient
                    .sql("""
                        INSERT INTO rtms_collection_work_unit (execution_id, lawd_cd, deal_ymd, state)
                        VALUES (:executionId, :lawdCd, :dealYmd, 'PLANNED')
                        ON CONFLICT (execution_id, lawd_cd, deal_ymd) DO NOTHING
                        """)
                    .param("executionId", plan.executionId().value())
                    .param("lawdCd", unit.lawdCd())
                    .param("dealYmd", unit.dealYmd())
                    .update();
        }

        List<RtmsCollectionWorkUnitPlan> stored = jdbcClient
                .sql("""
                    SELECT lawd_cd, deal_ymd
                    FROM rtms_collection_work_unit
                    WHERE execution_id = :executionId
                    ORDER BY lawd_cd, deal_ymd
                    """)
                .param("executionId", plan.executionId().value())
                .query((rs, rowNum) ->
                        new RtmsCollectionWorkUnitPlan(rs.getString("lawd_cd"), rs.getString("deal_ymd")))
                .list();
        List<RtmsCollectionWorkUnitPlan> expected = plan.workUnits().stream()
                .sorted(java.util.Comparator.comparing(RtmsCollectionWorkUnitPlan::lawdCd)
                        .thenComparing(RtmsCollectionWorkUnitPlan::dealYmd))
                .toList();
        if (!stored.equals(expected)) {
            throw new IllegalStateException("requestId work unit plan does not match persisted evidence");
        }
    }

    @Override
    public RtmsCollectionWorkUnitState findWorkUnitState(
            ExecutionCorrelationId executionId, String lawdCd, String dealYmd) {
        return jdbcClient
                .sql("""
                    SELECT state
                    FROM rtms_collection_work_unit
                    WHERE execution_id = :executionId AND lawd_cd = :lawdCd AND deal_ymd = :dealYmd
                    """)
                .param("executionId", executionId.value())
                .param("lawdCd", lawdCd)
                .param("dealYmd", dealYmd)
                .query(String.class)
                .optional()
                .map(RtmsCollectionWorkUnitState::valueOf)
                .orElseThrow(() -> new IllegalStateException("planned RTMS work unit not found"));
    }

    @Override
    public void markRunning(ExecutionCorrelationId executionId, String lawdCd, String dealYmd, Instant startedAt) {
        int updated = jdbcClient
                .sql("""
                    UPDATE rtms_collection_work_unit
                    SET state = 'RUNNING', started_at = COALESCE(started_at, :startedAt)
                    WHERE execution_id = :executionId AND lawd_cd = :lawdCd AND deal_ymd = :dealYmd
                      AND state IN ('PLANNED', 'RUNNING')
                    """)
                .param("executionId", executionId.value())
                .param("lawdCd", lawdCd)
                .param("dealYmd", dealYmd)
                .param("startedAt", offset(startedAt))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("RTMS work unit cannot transition to RUNNING");
        }
        jdbcClient.sql("""
                    UPDATE rtms_collection_execution
                    SET state = 'RUNNING'
                    WHERE execution_id = :executionId AND state IN ('PLANNED', 'RUNNING')
                    """).param("executionId", executionId.value()).update();
    }

    @Override
    public void markTerminal(
            ExecutionCorrelationId executionId,
            String lawdCd,
            String dealYmd,
            RtmsCollectionWorkUnitState state,
            Long rtmsIngestRunId,
            Instant completedAt) {
        int updated = jdbcClient
                .sql("""
                    UPDATE rtms_collection_work_unit
                    SET state = :state, rtms_ingest_run_id = :runId, completed_at = :completedAt
                    WHERE execution_id = :executionId AND lawd_cd = :lawdCd AND deal_ymd = :dealYmd
                      AND state = 'RUNNING'
                    """)
                .param("state", state.name())
                .param("runId", rtmsIngestRunId)
                .param("completedAt", offset(completedAt))
                .param("executionId", executionId.value())
                .param("lawdCd", lawdCd)
                .param("dealYmd", dealYmd)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("RTMS work unit cannot transition to terminal state");
        }
    }

    @Override
    public MarketInsightCoverage finish(ExecutionCorrelationId executionId, Instant completedAt) {
        CoverageRow row = jdbcClient
                .sql("""
                    SELECT e.collection_mode,
                           e.scope_type,
                           e.planned_work_unit_count,
                           count(*) FILTER (WHERE w.state = 'COMPLETED') AS completed_count,
                           count(*) FILTER (WHERE w.state = 'PARTIAL') AS partial_count,
                           count(*) FILTER (WHERE w.state = 'FAILED') AS failed_count,
                           count(*) FILTER (WHERE w.state IN ('PLANNED', 'RUNNING')) AS unfinished_count
                    FROM rtms_collection_execution e
                    JOIN rtms_collection_work_unit w ON w.execution_id = e.execution_id
                    WHERE e.execution_id = :executionId
                    GROUP BY e.collection_mode, e.scope_type, e.planned_work_unit_count
                    """)
                .param("executionId", executionId.value())
                .query((rs, rowNum) -> new CoverageRow(
                        RtmsCollectionMode.valueOf(rs.getString("collection_mode")),
                        RtmsCollectionScopeType.valueOf(rs.getString("scope_type")),
                        rs.getInt("planned_work_unit_count"),
                        rs.getInt("completed_count"),
                        rs.getInt("partial_count"),
                        rs.getInt("failed_count"),
                        rs.getInt("unfinished_count")))
                .single();
        RtmsCollectionExecutionState executionState = row.executionState();
        jdbcClient
                .sql("""
                    UPDATE rtms_collection_execution
                    SET state = :state,
                        completed_at = :completedAt,
                        failure_reason = :failureReason
                    WHERE execution_id = :executionId
                    """)
                .param("state", executionState.name())
                .param("completedAt", offset(completedAt))
                .param("failureReason", row.failureReason())
                .param("executionId", executionId.value())
                .update();
        return row.coverage();
    }

    private OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ExistingPlan(
            RtmsCollectionMode mode, RtmsCollectionScopeType scopeType, java.time.LocalDate runDate, int plannedCount) {
        boolean matches(RtmsCollectionExecutionPlan plan) {
            return mode == plan.collectionMode()
                    && scopeType == plan.scopeType()
                    && runDate.equals(plan.runDate())
                    && plannedCount == plan.workUnits().size();
        }
    }

    private record CoverageRow(
            RtmsCollectionMode mode,
            RtmsCollectionScopeType scopeType,
            int planned,
            int completed,
            int partial,
            int failed,
            int unfinished) {

        RtmsCollectionExecutionState executionState() {
            if (unfinished > 0) {
                return RtmsCollectionExecutionState.FAILED;
            }
            if (partial > 0 || failed > 0) {
                return RtmsCollectionExecutionState.PARTIAL;
            }
            return RtmsCollectionExecutionState.COMPLETED;
        }

        String failureReason() {
            return executionState() == RtmsCollectionExecutionState.COMPLETED
                    ? null
                    : "work unit summary: partial=" + partial + ", failed=" + failed + ", unfinished=" + unfinished;
        }

        MarketInsightCoverage coverage() {
            return new MarketInsightCoverage(mode, scopeType, planned, completed, partial, failed);
        }
    }
}
