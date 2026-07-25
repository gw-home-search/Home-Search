package com.home.infrastructure.event;

import com.home.application.event.PendingPropertyEvent;
import com.home.application.event.PropertyEventOutboxRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcPropertyEventOutboxRepository implements PropertyEventOutboxRepository {

    private final JdbcClient jdbcClient;

    JdbcPropertyEventOutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public List<PendingPropertyEvent> lockPending(int limit, Instant now) {
        return jdbcClient
                .sql("""
                    SELECT event_id,
                           topic_name,
                           aggregate_id,
                           attempt_count,
                           jsonb_build_object(
                               'eventId', event_id::text,
                               'eventType', event_type,
                               'schemaVersion', schema_version,
                               'occurredAt', occurred_at,
                               'producer', producer,
                               'aggregateType', aggregate_type,
                               'aggregateId', aggregate_id,
                               'aggregateVersion', aggregate_version,
                               'correlationId', correlation_id,
                               'causationId', causation_id,
                               'traceId', trace_id,
                               'payload', payload
                           )::text AS envelope_json
                    FROM event_outbox
                    WHERE published_at IS NULL
                      AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, created_at, event_id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """)
                .param("now", utc(now))
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PendingPropertyEvent(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("topic_name"),
                        resultSet.getString("aggregate_id"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("envelope_json")))
                .list();
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        int updated = jdbcClient
                .sql("""
                    UPDATE event_outbox
                    SET published_at = :publishedAt,
                        last_error = NULL
                    WHERE event_id = :eventId
                      AND published_at IS NULL
                    """)
                .param("publishedAt", utc(publishedAt))
                .param("eventId", eventId)
                .update();
        requireSingleUpdate(updated, "publish", eventId);
    }

    @Override
    public void markFailed(UUID eventId, Instant nextAttemptAt, String failureType) {
        int updated = jdbcClient
                .sql("""
                    UPDATE event_outbox
                    SET attempt_count = attempt_count + 1,
                        next_attempt_at = :nextAttemptAt,
                        last_error = :failureType
                    WHERE event_id = :eventId
                      AND published_at IS NULL
                    """)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("failureType", failureType)
                .param("eventId", eventId)
                .update();
        requireSingleUpdate(updated, "record failure for", eventId);
    }

    @Override
    public int deletePublishedBefore(Instant cutoff, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return jdbcClient
                .sql("""
                    SELECT public.delete_published_property_event_outbox_before(
                        :cutoff,
                        :limit
                    )
                    """)
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query(Integer.class)
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant), ZoneOffset.UTC);
    }

    private static void requireSingleUpdate(int updated, String action, UUID eventId) {
        if (updated != 1) {
            throw new IllegalStateException("failed to " + action + " outbox event " + eventId);
        }
    }
}
