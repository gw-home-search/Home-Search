package com.home.infrastructure.persistence.insight;

import com.home.application.insight.generation.MarketInsightBuildRepository;
import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.MarketInsightRejectionReason;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketInsightBuildRepository implements MarketInsightBuildRepository {

    private final JdbcClient jdbcClient;

    public JdbcMarketInsightBuildRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<MarketInsightSourceExecution> findLatestDailyNationwide(LocalDate runDate) {
        return jdbcClient
                .sql("""
                    SELECT e.execution_id, e.run_date, e.completed_at,
                           e.collection_mode, e.scope_type, e.planned_work_unit_count,
                           count(*) FILTER (WHERE w.state = 'COMPLETED') AS completed_count,
                           count(*) FILTER (WHERE w.state = 'PARTIAL') AS partial_count,
                           count(*) FILTER (WHERE w.state = 'FAILED') AS failed_count
                    FROM rtms_collection_execution e
                    JOIN rtms_collection_work_unit w ON w.execution_id = e.execution_id
                    WHERE e.collection_mode = 'DAILY'
                      AND e.scope_type = 'NATIONWIDE'
                      AND e.run_date = :runDate
                    GROUP BY e.execution_id, e.run_date, e.completed_at, e.collection_mode,
                             e.scope_type, e.planned_work_unit_count, e.started_at
                    ORDER BY e.started_at DESC
                    LIMIT 1
                    """)
                .param("runDate", runDate)
                .query(this::mapSource)
                .optional();
    }

    @Override
    public UUID publishDailyNationwide(MarketInsightSourceExecution source, Instant generatedAt) {
        Optional<UUID> existing = publishedDaily(source.runDate());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID snapshotId = UUID.randomUUID();
        insertSnapshot(snapshotId, source.runDate(), "BUILDING", source.completedAt(), source, null, generatedAt);
        insertDailyItems(snapshotId, source.executionId(), "DAILY_NEW_TRADE", "r.processed_at DESC, t.id ASC");
        insertDailyItems(
                snapshotId,
                source.executionId(),
                "DAILY_HIGHEST_DEAL",
                "t.deal_amount DESC, t.deal_date DESC, t.id ASC");
        jdbcClient.sql("""
                    UPDATE market_insight_snapshot
                    SET build_status = 'PUBLISHED'
                    WHERE snapshot_id = :snapshotId AND build_status = 'BUILDING'
                    """).param("snapshotId", snapshotId).update();
        return snapshotId;
    }

    @Override
    public UUID rejectDailyNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        UUID snapshotId = UUID.randomUUID();
        insertSnapshot(
                snapshotId,
                runDate,
                "REJECTED",
                source == null || source.completedAt() == null ? generatedAt : source.completedAt(),
                source,
                reason,
                generatedAt);
        return snapshotId;
    }

    private void insertSnapshot(
            UUID snapshotId,
            LocalDate runDate,
            String buildStatus,
            Instant dataCutoff,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        MarketInsightCoverage coverage = source == null
                ? new MarketInsightCoverage(RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 0, 0, 0, 0)
                : source.coverage();
        jdbcClient
                .sql("""
                    INSERT INTO market_insight_snapshot (
                        snapshot_id, period_type, period_start, period_end, scope_type, region_code,
                        build_status, data_cutoff, source_execution_id,
                        planned_work_unit_count, completed_work_unit_count,
                        partial_work_unit_count, failed_work_unit_count,
                        generated_at, rejection_reason
                    ) VALUES (
                        :snapshotId, 'DAILY', :runDate, :runDate, 'NATIONWIDE', NULL,
                        :buildStatus, :dataCutoff, :sourceExecutionId,
                        :plannedCount, :completedCount, :partialCount, :failedCount,
                        :generatedAt, :rejectionReason
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("runDate", runDate)
                .param("buildStatus", buildStatus)
                .param("dataCutoff", offset(dataCutoff))
                .param("sourceExecutionId", source == null ? null : source.executionId())
                .param("plannedCount", coverage.plannedCount())
                .param("completedCount", coverage.completedCount())
                .param("partialCount", coverage.partialCount())
                .param("failedCount", coverage.failedCount())
                .param("generatedAt", offset(generatedAt))
                .param("rejectionReason", reason == null ? null : reason.name())
                .update();
    }

    private void insertDailyItems(UUID snapshotId, UUID executionId, String metricType, String staticOrderBy) {
        if (!java.util.Set.of("r.processed_at DESC, t.id ASC", "t.deal_amount DESC, t.deal_date DESC, t.id ASC")
                .contains(staticOrderBy)) {
            throw new IllegalArgumentException("unsupported insight ordering");
        }
        String sql = """
            WITH ranked AS (
                SELECT t.id AS trade_id,
                       t.deal_date AS trade_deal_date,
                       t.complex_id,
                       t.deal_amount,
                       t.excl_area,
                       r.processed_at AS disclosed_at,
                       CASE WHEN t.deleted_at IS NULL THEN 'ACTIVE' ELSE 'CANCELED' END AS trade_status,
                       t.deleted_at AS canceled_at,
                       row_number() OVER (ORDER BY %s) AS item_rank
                FROM raw_trade_ingest r
                JOIN trade t ON t.raw_ingest_id = r.id
                WHERE r.execution_correlation_id = :executionId
                  AND r.status = 'NORMALIZED'
            )
            INSERT INTO market_insight_trade_item (
                snapshot_id, metric_type, rank,
                trade_id, trade_deal_date, complex_id,
                deal_amount, excl_area, deal_date, disclosed_at,
                captured_trade_status, canceled_at
            )
            SELECT :snapshotId, :metricType, item_rank,
                   trade_id, trade_deal_date, complex_id,
                   deal_amount, excl_area, trade_deal_date, disclosed_at,
                   trade_status, canceled_at
            FROM ranked
            WHERE item_rank <= 50
            """.formatted(staticOrderBy);
        jdbcClient
                .sql(sql)
                .param("executionId", executionId)
                .param("snapshotId", snapshotId)
                .param("metricType", metricType)
                .update();
    }

    private Optional<UUID> publishedDaily(LocalDate runDate) {
        return jdbcClient.sql("""
                    SELECT snapshot_id
                    FROM market_insight_snapshot
                    WHERE period_type = 'DAILY'
                      AND period_start = :runDate
                      AND period_end = :runDate
                      AND scope_type = 'NATIONWIDE'
                      AND region_code IS NULL
                      AND build_status = 'PUBLISHED'
                    """).param("runDate", runDate).query(UUID.class).optional();
    }

    private MarketInsightSourceExecution mapSource(ResultSet rs, int rowNum) throws SQLException {
        return new MarketInsightSourceExecution(
                rs.getObject("execution_id", UUID.class),
                rs.getObject("run_date", LocalDate.class),
                instant(rs, "completed_at"),
                new MarketInsightCoverage(
                        RtmsCollectionMode.valueOf(rs.getString("collection_mode")),
                        RtmsCollectionScopeType.valueOf(rs.getString("scope_type")),
                        rs.getInt("planned_work_unit_count"),
                        rs.getInt("completed_count"),
                        rs.getInt("partial_count"),
                        rs.getInt("failed_count")));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
