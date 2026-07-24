package com.home.infrastructure.persistence.insight;

import com.home.application.insight.generation.MarketInsightSourceExecution;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInsightExecutionReader {

    private final JdbcClient jdbcClient;

    JdbcInsightExecutionReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    Optional<MarketInsightSourceExecution> findLatestDailyNationwide(LocalDate runDate) {
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

    List<MarketInsightSourceExecution> findLatestDailyNationwideForWeek(LocalDate weekStart) {
        return jdbcClient
                .sql("""
                    WITH latest AS (
                        SELECT DISTINCT ON (e.run_date)
                               e.execution_id, e.run_date, e.completed_at, e.collection_mode,
                               e.scope_type, e.planned_work_unit_count, e.state, e.started_at
                        FROM rtms_collection_execution e
                        WHERE e.collection_mode = 'DAILY'
                          AND e.scope_type = 'NATIONWIDE'
                          AND e.run_date BETWEEN :weekStart AND :weekEnd
                        ORDER BY e.run_date, e.started_at DESC
                    )
                    SELECT latest.execution_id, latest.run_date, latest.completed_at,
                           latest.collection_mode, latest.scope_type, latest.planned_work_unit_count,
                           count(*) FILTER (WHERE w.state = 'COMPLETED') AS completed_count,
                           count(*) FILTER (WHERE w.state = 'PARTIAL') AS partial_count,
                           count(*) FILTER (WHERE w.state = 'FAILED') AS failed_count
                    FROM latest
                    JOIN rtms_collection_work_unit w ON w.execution_id = latest.execution_id
                    WHERE latest.state = 'COMPLETED'
                      AND latest.completed_at IS NOT NULL
                    GROUP BY latest.execution_id, latest.run_date, latest.completed_at,
                             latest.collection_mode, latest.scope_type, latest.planned_work_unit_count
                    ORDER BY latest.run_date
                    """)
                .param("weekStart", weekStart)
                .param("weekEnd", weekStart.plusDays(6))
                .query(this::mapSource)
                .list();
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
}
