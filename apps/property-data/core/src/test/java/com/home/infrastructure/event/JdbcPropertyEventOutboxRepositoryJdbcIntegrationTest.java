package com.home.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdbcPropertyEventOutboxRepositoryJdbcIntegrationTest extends JdbcPostgresTestSupport {

    private static final UUID EVENT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("due outbox만 envelope로 lock하고 실패 retry와 성공 완료 상태를 보존한다")
    void locksDueEventAndPersistsRetryThenPublication() throws Exception {
        insertOutboxEvent(EVENT_ID, "snapshot-1", NOW.minusSeconds(10), NOW.minusSeconds(1));
        insertOutboxEvent(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174011"),
                "snapshot-2",
                NOW.minusSeconds(10),
                NOW.plusSeconds(60));
        JdbcPropertyEventOutboxRepository repository = new JdbcPropertyEventOutboxRepository(jdbcClient);

        var pending = repository.lockPending(10, NOW);

        assertThat(pending).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.topicName()).isEqualTo("property.insight-events.v1");
            assertThat(event.aggregateId()).isEqualTo("snapshot-1");
            assertThat(event.attemptCount()).isZero();
            var envelope = new ObjectMapper().readTree(event.envelopeJson());
            assertThat(envelope.get("eventId").asText()).isEqualTo(EVENT_ID.toString());
            assertThat(envelope.get("eventType").asText()).isEqualTo("InsightPublished");
            assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(envelope.get("payload").get("snapshotId").asText()).isEqualTo("snapshot-1");
        });

        repository.markFailed(EVENT_ID, NOW.plusSeconds(1), "PropertyEventPublishException");
        assertThat(jdbcClient
                        .sql("""
                            SELECT attempt_count, next_attempt_at, last_error
                            FROM event_outbox
                            WHERE event_id = :eventId
                            """)
                        .param("eventId", EVENT_ID)
                        .query((resultSet, rowNumber) -> java.util.List.of(
                                resultSet.getInt("attempt_count"),
                                resultSet
                                        .getObject("next_attempt_at", OffsetDateTime.class)
                                        .toInstant(),
                                resultSet.getString("last_error")))
                        .single())
                .containsExactly(1, NOW.plusSeconds(1), "PropertyEventPublishException");

        repository.markPublished(EVENT_ID, NOW.plusSeconds(2));
        assertThat(jdbcClient
                        .sql("SELECT published_at FROM event_outbox WHERE event_id = :eventId")
                        .param("eventId", EVENT_ID)
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(NOW.plusSeconds(2));
        assertThatThrownBy(() -> repository.markPublished(EVENT_ID, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("30일이 지난 published outbox만 제한된 건수만큼 삭제한다")
    void deletesOnlyExpiredPublishedEventsWithinLimit() {
        UUID firstExpired = UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
        UUID secondExpired = UUID.fromString("123e4567-e89b-12d3-a456-426614174021");
        UUID recentPublished = UUID.fromString("123e4567-e89b-12d3-a456-426614174022");
        UUID unpublished = UUID.fromString("123e4567-e89b-12d3-a456-426614174023");
        insertOutboxEvent(firstExpired, "snapshot-expired-1", NOW.minusSeconds(40L * 86_400), NOW);
        insertOutboxEvent(secondExpired, "snapshot-expired-2", NOW.minusSeconds(35L * 86_400), NOW);
        insertOutboxEvent(recentPublished, "snapshot-recent", NOW.minusSeconds(29L * 86_400), NOW);
        insertOutboxEvent(unpublished, "snapshot-pending", NOW.minusSeconds(40L * 86_400), NOW);
        JdbcPropertyEventOutboxRepository repository = new JdbcPropertyEventOutboxRepository(jdbcClient);
        repository.markPublished(firstExpired, NOW.minusSeconds(40L * 86_400));
        repository.markPublished(secondExpired, NOW.minusSeconds(35L * 86_400));
        repository.markPublished(recentPublished, NOW.minusSeconds(29L * 86_400));

        int deleted = repository.deletePublishedBefore(NOW.minusSeconds(30L * 86_400), 1);

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcClient
                        .sql("SELECT event_id FROM event_outbox ORDER BY event_id")
                        .query(UUID.class)
                        .list())
                .containsExactly(secondExpired, recentPublished, unpublished);
    }

    private void insertOutboxEvent(UUID eventId, String aggregateId, Instant occurredAt, Instant nextAttemptAt) {
        jdbcClient
                .sql("""
                    INSERT INTO event_outbox (
                        event_id, topic_name, event_type, schema_version, occurred_at,
                        producer, aggregate_type, aggregate_id, aggregate_version,
                        correlation_id, causation_id, trace_id, payload, next_attempt_at
                    ) VALUES (
                        :eventId, 'property.insight-events.v1', 'InsightPublished', 1, :occurredAt,
                        'property-data', 'InsightSnapshot', :aggregateId, 1,
                        'correlation-1', NULL, 'trace-1',
                        jsonb_build_object('snapshotId', :aggregateId, 'insightKind', 'DAILY'),
                        :nextAttemptAt
                    )
                    """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                .update();
    }
}
