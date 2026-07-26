package com.home.application.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class PropertyEventOutboxRelayService {

    private static final int MAX_BATCH_SIZE = 100;

    private final PropertyEventOutboxRepository repository;
    private final PropertyEventPublisher publisher;

    public PropertyEventOutboxRelayService(PropertyEventOutboxRepository repository, PropertyEventPublisher publisher) {
        this.repository = Objects.requireNonNull(repository);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Transactional
    public PropertyEventRelayResult relayBatch(int limit, Instant now) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("relay batch limit must be between 1 and 100");
        }
        Objects.requireNonNull(now);

        var events = repository.lockPending(limit, now);
        int publishedCount = 0;
        int failedCount = 0;
        for (PendingPropertyEvent event : events) {
            try {
                publisher.publish(event.topicName(), event.aggregateId(), event.envelopeJson());
                repository.markPublished(event.eventId(), now);
                publishedCount++;
            } catch (RuntimeException exception) {
                repository.markFailed(
                        event.eventId(),
                        now.plus(retryDelay(event.attemptCount())),
                        exception.getClass().getSimpleName());
                failedCount++;
            }
        }
        return new PropertyEventRelayResult(events.size(), publishedCount, failedCount);
    }

    private Duration retryDelay(int attemptCount) {
        return switch (attemptCount) {
            case 0 -> Duration.ofSeconds(1);
            case 1 -> Duration.ofSeconds(5);
            default -> Duration.ofSeconds(30);
        };
    }
}
