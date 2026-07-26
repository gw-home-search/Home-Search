package com.home.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PropertyEventOutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("Kafka publish 성공 ack 뒤에만 outbox를 발행 완료로 표시한다")
    void marksPublishedOnlyAfterPublisherAcknowledges() {
        PendingPropertyEvent event = pendingEvent(0);
        RecordingRepository repository = new RecordingRepository(List.of(event));
        List<String> callOrder = new ArrayList<>();
        PropertyEventPublisher publisher = (topic, key, envelope) -> {
            callOrder.add("publish");
            assertThat(topic).isEqualTo("property.insight-events.v1");
            assertThat(key).isEqualTo("snapshot-1");
            assertThat(envelope).contains("\"eventId\":\"" + event.eventId() + "\"");
        };
        repository.callOrder = callOrder;

        var result = new PropertyEventOutboxRelayService(repository, publisher).relayBatch(10, NOW);

        assertThat(result).isEqualTo(new PropertyEventRelayResult(1, 1, 0));
        assertThat(callOrder).containsExactly("publish", "markPublished");
        assertThat(repository.published).containsExactly(new Publication(event.eventId(), NOW));
        assertThat(repository.failures).isEmpty();
    }

    @Test
    @DisplayName("Kafka publish 실패는 row를 유지하고 1초 5초 30초 backoff로 재시도 예약한다")
    void retainsFailedRowsWithBoundedBackoff() {
        for (int attempt = 0; attempt < 4; attempt++) {
            PendingPropertyEvent event = pendingEvent(attempt);
            RecordingRepository repository = new RecordingRepository(List.of(event));
            PropertyEventPublisher publisher = (topic, key, envelope) -> {
                throw new IllegalStateException("broker details must not be persisted");
            };

            var result = new PropertyEventOutboxRelayService(repository, publisher).relayBatch(10, NOW);

            Duration expectedDelay =
                    switch (attempt) {
                        case 0 -> Duration.ofSeconds(1);
                        case 1 -> Duration.ofSeconds(5);
                        default -> Duration.ofSeconds(30);
                    };
            assertThat(result).isEqualTo(new PropertyEventRelayResult(1, 0, 1));
            assertThat(repository.published).isEmpty();
            assertThat(repository.failures)
                    .containsExactly(
                            new PublicationFailure(event.eventId(), NOW.plus(expectedDelay), "IllegalStateException"));
        }
    }

    @Test
    @DisplayName("relay batch limit은 과도한 DB lock을 막도록 1부터 100 사이만 허용한다")
    void rejectsUnsafeBatchLimit() {
        RecordingRepository repository = new RecordingRepository(List.of());
        PropertyEventPublisher publisher = (topic, key, envelope) -> {};
        var relay = new PropertyEventOutboxRelayService(repository, publisher);

        assertThatThrownBy(() -> relay.relayBatch(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay.relayBatch(101, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay.relayBatch(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("pending event는 필수 envelope identity와 non-negative attempt를 강제한다")
    void rejectsInvalidPendingEvent() {
        UUID eventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

        assertThatThrownBy(() -> new PendingPropertyEvent(eventId, "", "aggregate-1", 0, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingPropertyEvent(eventId, "topic", "", 0, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingPropertyEvent(eventId, "topic", "aggregate-1", -1, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingPropertyEvent(eventId, "topic", "aggregate-1", 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PendingPropertyEvent pendingEvent(int attemptCount) {
        UUID eventId = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        return new PendingPropertyEvent(
                eventId,
                "property.insight-events.v1",
                "snapshot-1",
                attemptCount,
                "{\"eventId\":\"" + eventId + "\",\"payload\":{}}");
    }

    private record Publication(UUID eventId, Instant publishedAt) {}

    private record PublicationFailure(UUID eventId, Instant nextAttemptAt, String failureType) {}

    private static final class RecordingRepository implements PropertyEventOutboxRepository {

        private final List<PendingPropertyEvent> pending;
        private final List<Publication> published = new ArrayList<>();
        private final List<PublicationFailure> failures = new ArrayList<>();
        private List<String> callOrder = new ArrayList<>();

        private RecordingRepository(List<PendingPropertyEvent> pending) {
            this.pending = pending;
        }

        @Override
        public List<PendingPropertyEvent> lockPending(int limit, Instant now) {
            return pending;
        }

        @Override
        public void markPublished(UUID eventId, Instant publishedAt) {
            callOrder.add("markPublished");
            published.add(new Publication(eventId, publishedAt));
        }

        @Override
        public void markFailed(UUID eventId, Instant nextAttemptAt, String failureType) {
            failures.add(new PublicationFailure(eventId, nextAttemptAt, failureType));
        }

        @Override
        public int deletePublishedBefore(Instant cutoff, int limit) {
            throw new UnsupportedOperationException("not used by relay tests");
        }
    }
}
