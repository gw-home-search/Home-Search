package com.home.infrastructure.persistence.insight;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcInsightOutboxWriter {

    private final JdbcClient jdbcClient;

    JdbcInsightOutboxWriter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    void writePublished(
            UUID sourceExecutionId, String periodType, String insightKind, int expectedScopeCount, Instant occurredAt) {
        int inserted = jdbcClient
                .sql("""
                    INSERT INTO event_outbox (
                        event_id, topic_name, event_type, schema_version, occurred_at,
                        producer, aggregate_type, aggregate_id, aggregate_version,
                        correlation_id, causation_id, trace_id, payload
                    )
                    SELECT gen_random_uuid(), 'property.insight-events.v1',
                           'InsightPublished', 1, :occurredAt,
                           'property-data', 'InsightSnapshot', snapshot.snapshot_id::text, 1,
                           COALESCE(snapshot.source_execution_id::text, snapshot.snapshot_id::text),
                           NULL,
                           COALESCE(snapshot.source_execution_id::text, snapshot.snapshot_id::text),
                           jsonb_build_object(
                               'snapshotId', snapshot.snapshot_id::text,
                               'insightKind', :insightKind,
                               'scopeType', snapshot.scope_type,
                               'regionCode', snapshot.region_code,
                               'dataCutoff', snapshot.data_cutoff
                           )
                    FROM market_insight_snapshot snapshot
                    WHERE snapshot.source_execution_id = :sourceExecutionId
                      AND snapshot.period_type = :periodType
                      AND snapshot.build_status = 'PUBLISHED'
                    ON CONFLICT (event_type, aggregate_id, aggregate_version) DO NOTHING
                    """)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("sourceExecutionId", sourceExecutionId)
                .param("periodType", periodType)
                .param("insightKind", insightKind)
                .update();
        if (inserted != 0 && inserted != expectedScopeCount) {
            throw new IllegalStateException("insight outbox publication count mismatch");
        }
    }

    void writeWeeklyPublished(LocalDate weekStart, UUID correlationId, int expectedScopeCount, Instant occurredAt) {
        int inserted = jdbcClient
                .sql("""
                    INSERT INTO event_outbox (
                        event_id, topic_name, event_type, schema_version, occurred_at,
                        producer, aggregate_type, aggregate_id, aggregate_version,
                        correlation_id, causation_id, trace_id, payload
                    )
                    SELECT gen_random_uuid(), 'property.insight-events.v1',
                           'InsightPublished', 1, :occurredAt,
                           'property-data', 'InsightSnapshot', snapshot.snapshot_id::text, 1,
                           :correlationId, NULL, :correlationId,
                           jsonb_build_object(
                               'snapshotId', snapshot.snapshot_id::text,
                               'insightKind', 'WEEKLY',
                               'scopeType', snapshot.scope_type,
                               'regionCode', snapshot.region_code,
                               'dataCutoff', snapshot.data_cutoff
                           )
                    FROM market_insight_snapshot snapshot
                    WHERE snapshot.period_type = 'WEEKLY'
                      AND snapshot.period_start = :weekStart
                      AND snapshot.build_status = 'PUBLISHED'
                    ON CONFLICT (event_type, aggregate_id, aggregate_version) DO NOTHING
                    """)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("correlationId", correlationId.toString())
                .param("weekStart", weekStart)
                .update();
        if (inserted != 0 && inserted != expectedScopeCount) {
            throw new IllegalStateException("weekly insight outbox publication count mismatch");
        }
    }
}
