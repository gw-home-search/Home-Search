package com.home.infrastructure.persistence.insight;

import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.MarketInsightRejectionReason;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMarketInsightSnapshotWriter {

    private static final int EXPECTED_SIDO_COUNT = 17;

    private final JdbcClient jdbcClient;

    JdbcMarketInsightSnapshotWriter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    Optional<UUID> findPublishedDailyNationwide(LocalDate runDate) {
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

    Optional<UUID> findPublishedWeeklyNationwide(LocalDate weekStart) {
        return jdbcClient
                .sql("""
                    SELECT snapshot_id
                    FROM market_insight_snapshot
                    WHERE period_type = 'WEEKLY'
                      AND period_start = :weekStart
                      AND period_end = :weekEnd
                      AND scope_type = 'NATIONWIDE'
                      AND region_code IS NULL
                      AND build_status = 'PUBLISHED'
                    """)
                .param("weekStart", weekStart)
                .param("weekEnd", weekStart.plusDays(6))
                .query(UUID.class)
                .optional();
    }

    Optional<PublishedRollingSnapshot> findPublishedRollingNationwide(LocalDate periodEnd) {
        return jdbcClient
                .sql("""
                    SELECT snapshot_id, source_execution_id
                    FROM market_insight_snapshot
                    WHERE period_type = 'ROLLING_7D'
                      AND period_start = :periodStart
                      AND period_end = :periodEnd
                      AND scope_type = 'NATIONWIDE'
                      AND region_code IS NULL
                      AND build_status = 'PUBLISHED'
                    """)
                .param("periodStart", periodEnd.minusDays(6))
                .param("periodEnd", periodEnd)
                .query((rs, rowNum) -> new PublishedRollingSnapshot(
                        rs.getObject("snapshot_id", UUID.class), rs.getObject("source_execution_id", UUID.class)))
                .optional();
    }

    SnapshotSet createDailyScopes(MarketInsightSourceExecution source, Instant generatedAt) {
        List<String> sidoCodes = jdbcClient.sql("""
                    SELECT code
                    FROM region
                    WHERE parent_id IS NULL AND region_type = 'si-do'
                    ORDER BY code
                    """).query(String.class).list();
        if (sidoCodes.size() != EXPECTED_SIDO_COUNT) {
            throw new IllegalStateException("daily insight publication requires exactly 17 si-do regions");
        }

        UUID nationwideId = UUID.randomUUID();
        insertSnapshot(
                nationwideId,
                source.runDate(),
                "NATIONWIDE",
                null,
                "BUILDING",
                source.completedAt(),
                source,
                null,
                generatedAt);
        for (String sidoCode : sidoCodes) {
            insertSnapshot(
                    UUID.randomUUID(),
                    source.runDate(),
                    "SIDO",
                    sidoCode,
                    "BUILDING",
                    source.completedAt(),
                    source,
                    null,
                    generatedAt);
        }
        return new SnapshotSet(nationwideId, source.executionId(), 1 + sidoCodes.size());
    }

    RollingSnapshotSet createRollingScopes(MarketInsightSourceExecution source, Instant generatedAt) {
        List<String> sidoCodes = rootSidoCodes();
        List<UUID> snapshotIds = new java.util.ArrayList<>();
        UUID nationwideId = UUID.randomUUID();
        snapshotIds.add(nationwideId);
        insertRollingSnapshot(nationwideId, source, "NATIONWIDE", null, "BUILDING", null, generatedAt);
        for (String sidoCode : sidoCodes) {
            UUID snapshotId = UUID.randomUUID();
            snapshotIds.add(snapshotId);
            insertRollingSnapshot(snapshotId, source, "SIDO", sidoCode, "BUILDING", null, generatedAt);
        }
        return new RollingSnapshotSet(nationwideId, source.executionId(), source.runDate(), List.copyOf(snapshotIds));
    }

    void publish(SnapshotSet snapshots) {
        int updated = jdbcClient
                .sql("""
                    UPDATE market_insight_snapshot
                    SET build_status = 'PUBLISHED'
                    WHERE source_execution_id = :executionId
                      AND build_status = 'BUILDING'
                    """)
                .param("executionId", snapshots.executionId())
                .update();
        if (updated != snapshots.scopeCount()) {
            throw new IllegalStateException("daily insight scope publication count does not match the prepared set");
        }
    }

    void supersedeAndPublishRolling(RollingSnapshotSet snapshots) {
        int superseded = jdbcClient
                .sql("""
                    UPDATE market_insight_snapshot previous
                    SET build_status = 'SUPERSEDED',
                        superseded_by_snapshot_id = replacement.snapshot_id
                    FROM market_insight_snapshot replacement
                    WHERE previous.period_type = 'ROLLING_7D'
                      AND previous.period_start = :periodStart
                      AND previous.period_end = :periodEnd
                      AND previous.build_status = 'PUBLISHED'
                      AND replacement.period_type = 'ROLLING_7D'
                      AND replacement.period_start = previous.period_start
                      AND replacement.period_end = previous.period_end
                      AND replacement.source_execution_id = :executionId
                      AND replacement.build_status = 'BUILDING'
                      AND replacement.scope_type = previous.scope_type
                      AND replacement.region_code IS NOT DISTINCT FROM previous.region_code
                    """)
                .param("periodStart", snapshots.periodEnd().minusDays(6))
                .param("periodEnd", snapshots.periodEnd())
                .param("executionId", snapshots.executionId())
                .update();
        if (superseded != 0 && superseded != snapshots.snapshotIds().size()) {
            throw new IllegalStateException("rolling insight superseded scope count is incomplete");
        }
        int published = jdbcClient
                .sql("""
                    UPDATE market_insight_snapshot
                    SET build_status = 'PUBLISHED'
                    WHERE source_execution_id = :executionId
                      AND period_type = 'ROLLING_7D'
                      AND build_status = 'BUILDING'
                    """)
                .param("executionId", snapshots.executionId())
                .update();
        if (published != snapshots.snapshotIds().size()) {
            throw new IllegalStateException("rolling insight scope publication count does not match the prepared set");
        }
    }

    WeeklySnapshotSet createWeeklyScopes(
            LocalDate weekStart, List<MarketInsightSourceExecution> sources, Instant generatedAt) {
        List<String> sidoCodes = rootSidoCodes();
        Instant dataCutoff = sources.stream()
                .map(MarketInsightSourceExecution::completedAt)
                .max(Instant::compareTo)
                .orElse(generatedAt);
        int planned = sources.stream()
                .mapToInt(source -> source.coverage().plannedCount())
                .sum();
        int completed = sources.stream()
                .mapToInt(source -> source.coverage().completedCount())
                .sum();
        int partial = sources.stream()
                .mapToInt(source -> source.coverage().partialCount())
                .sum();
        int failed = sources.stream()
                .mapToInt(source -> source.coverage().failedCount())
                .sum();
        List<UUID> snapshotIds = new java.util.ArrayList<>();
        UUID nationwideId = UUID.randomUUID();
        snapshotIds.add(nationwideId);
        insertWeeklySnapshot(
                nationwideId,
                weekStart,
                "NATIONWIDE",
                null,
                "BUILDING",
                dataCutoff,
                planned,
                completed,
                partial,
                failed,
                null,
                generatedAt);
        for (String sidoCode : sidoCodes) {
            UUID snapshotId = UUID.randomUUID();
            snapshotIds.add(snapshotId);
            insertWeeklySnapshot(
                    snapshotId,
                    weekStart,
                    "SIDO",
                    sidoCode,
                    "BUILDING",
                    dataCutoff,
                    planned,
                    completed,
                    partial,
                    failed,
                    null,
                    generatedAt);
        }
        for (UUID snapshotId : snapshotIds) {
            for (MarketInsightSourceExecution source : sources) {
                insertLineage(snapshotId, source);
            }
        }
        return new WeeklySnapshotSet(nationwideId, List.copyOf(snapshotIds));
    }

    void publishWeekly(WeeklySnapshotSet snapshots) {
        int updated = 0;
        for (UUID snapshotId : snapshots.snapshotIds()) {
            updated += jdbcClient.sql("""
                        UPDATE market_insight_snapshot
                        SET build_status = 'PUBLISHED'
                        WHERE snapshot_id = :snapshotId AND build_status = 'BUILDING'
                        """).param("snapshotId", snapshotId).update();
        }
        if (updated != snapshots.snapshotIds().size()) {
            throw new IllegalStateException("weekly insight scope publication count does not match the prepared set");
        }
    }

    UUID rejectDailyNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        UUID snapshotId = UUID.randomUUID();
        insertSnapshot(
                snapshotId,
                runDate,
                "NATIONWIDE",
                null,
                "REJECTED",
                source == null || source.completedAt() == null ? generatedAt : source.completedAt(),
                source,
                reason,
                generatedAt);
        return snapshotId;
    }

    UUID rejectWeeklyNationwide(
            LocalDate weekStart,
            List<MarketInsightSourceExecution> sources,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        UUID snapshotId = UUID.randomUUID();
        Instant cutoff = sources.stream()
                .map(MarketInsightSourceExecution::completedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(generatedAt);
        int planned = sources.stream()
                .mapToInt(source -> source.coverage().plannedCount())
                .sum();
        int completed = sources.stream()
                .mapToInt(source -> source.coverage().completedCount())
                .sum();
        int partial = sources.stream()
                .mapToInt(source -> source.coverage().partialCount())
                .sum();
        int failed = sources.stream()
                .mapToInt(source -> source.coverage().failedCount())
                .sum();
        insertWeeklySnapshot(
                snapshotId,
                weekStart,
                "NATIONWIDE",
                null,
                "REJECTED",
                cutoff,
                planned,
                completed,
                partial,
                failed,
                reason,
                generatedAt);
        for (MarketInsightSourceExecution source : sources) insertLineage(snapshotId, source);
        return snapshotId;
    }

    UUID rejectRollingNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        UUID snapshotId = UUID.randomUUID();
        MarketInsightSourceExecution effectiveSource = source == null
                ? new MarketInsightSourceExecution(
                        null,
                        runDate,
                        null,
                        new MarketInsightCoverage(
                                RtmsCollectionMode.DAILY, RtmsCollectionScopeType.NATIONWIDE, 0, 0, 0, 0))
                : source;
        insertRollingSnapshot(snapshotId, effectiveSource, "NATIONWIDE", null, "REJECTED", reason, generatedAt);
        return snapshotId;
    }

    private List<String> rootSidoCodes() {
        List<String> sidoCodes = jdbcClient.sql("""
                    SELECT code FROM region
                    WHERE parent_id IS NULL AND region_type = 'si-do'
                    ORDER BY code
                    """).query(String.class).list();
        if (sidoCodes.size() != EXPECTED_SIDO_COUNT) {
            throw new IllegalStateException("weekly insight publication requires exactly 17 si-do regions");
        }
        return sidoCodes;
    }

    private void insertWeeklySnapshot(
            UUID snapshotId,
            LocalDate weekStart,
            String scopeType,
            String regionCode,
            String buildStatus,
            Instant dataCutoff,
            int planned,
            int completed,
            int partial,
            int failed,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        jdbcClient
                .sql("""
                    INSERT INTO market_insight_snapshot (
                        snapshot_id, period_type, period_start, period_end, scope_type, region_code,
                        build_status, data_cutoff, source_execution_id,
                        planned_work_unit_count, completed_work_unit_count,
                        partial_work_unit_count, failed_work_unit_count, generated_at, rejection_reason
                    ) VALUES (
                        :snapshotId, 'WEEKLY', :weekStart, :weekEnd, :scopeType, :regionCode,
                        :buildStatus, :dataCutoff, NULL,
                        :planned, :completed, :partial, :failed, :generatedAt, :reason
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("weekStart", weekStart)
                .param("weekEnd", weekStart.plusDays(6))
                .param("scopeType", scopeType)
                .param("regionCode", regionCode)
                .param("buildStatus", buildStatus)
                .param("dataCutoff", offset(dataCutoff))
                .param("planned", planned)
                .param("completed", completed)
                .param("partial", partial)
                .param("failed", failed)
                .param("generatedAt", offset(generatedAt))
                .param("reason", reason == null ? null : reason.name())
                .update();
    }

    private void insertRollingSnapshot(
            UUID snapshotId,
            MarketInsightSourceExecution source,
            String scopeType,
            String regionCode,
            String buildStatus,
            MarketInsightRejectionReason reason,
            Instant generatedAt) {
        MarketInsightCoverage coverage = source.coverage();
        Instant dataCutoff = source.completedAt() == null ? generatedAt : source.completedAt();
        jdbcClient
                .sql("""
                    INSERT INTO market_insight_snapshot (
                        snapshot_id, period_type, period_start, period_end, scope_type, region_code,
                        build_status, data_cutoff, source_execution_id,
                        planned_work_unit_count, completed_work_unit_count,
                        partial_work_unit_count, failed_work_unit_count, generated_at, rejection_reason
                    ) VALUES (
                        :snapshotId, 'ROLLING_7D', :periodStart, :periodEnd, :scopeType, :regionCode,
                        :buildStatus, :dataCutoff, :sourceExecutionId,
                        :planned, :completed, :partial, :failed, :generatedAt, :reason
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("periodStart", source.runDate().minusDays(6))
                .param("periodEnd", source.runDate())
                .param("scopeType", scopeType)
                .param("regionCode", regionCode)
                .param("buildStatus", buildStatus)
                .param("dataCutoff", offset(dataCutoff))
                .param("sourceExecutionId", source.executionId())
                .param("planned", coverage.plannedCount())
                .param("completed", coverage.completedCount())
                .param("partial", coverage.partialCount())
                .param("failed", coverage.failedCount())
                .param("generatedAt", offset(generatedAt))
                .param("reason", reason == null ? null : reason.name())
                .update();
    }

    private void insertLineage(UUID snapshotId, MarketInsightSourceExecution source) {
        jdbcClient
                .sql("""
                    INSERT INTO market_insight_snapshot_execution (
                        snapshot_id, execution_id, run_date
                    ) VALUES (:snapshotId, :executionId, :runDate)
                    """)
                .param("snapshotId", snapshotId)
                .param("executionId", source.executionId())
                .param("runDate", source.runDate())
                .update();
    }

    private void insertSnapshot(
            UUID snapshotId,
            LocalDate runDate,
            String scopeType,
            String regionCode,
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
                        :snapshotId, 'DAILY', :runDate, :runDate, :scopeType, :regionCode,
                        :buildStatus, :dataCutoff, :sourceExecutionId,
                        :plannedCount, :completedCount, :partialCount, :failedCount,
                        :generatedAt, :rejectionReason
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("runDate", runDate)
                .param("scopeType", scopeType)
                .param("regionCode", regionCode)
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

    private OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record SnapshotSet(UUID nationwideSnapshotId, UUID executionId, int scopeCount) {}

    record WeeklySnapshotSet(UUID nationwideSnapshotId, List<UUID> snapshotIds) {}

    record PublishedRollingSnapshot(UUID snapshotId, UUID executionId) {}

    record RollingSnapshotSet(
            UUID nationwideSnapshotId, UUID executionId, LocalDate periodEnd, List<UUID> snapshotIds) {}
}
