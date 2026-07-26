package com.home.application.event;

import java.util.Objects;
import java.util.UUID;

public record PendingPropertyEvent(
        UUID eventId, String topicName, String aggregateId, int attemptCount, String envelopeJson) {

    public PendingPropertyEvent {
        Objects.requireNonNull(eventId);
        if (topicName == null || topicName.isBlank()) {
            throw new IllegalArgumentException("topicName must not be blank");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        if (envelopeJson == null || envelopeJson.isBlank()) {
            throw new IllegalArgumentException("envelopeJson must not be blank");
        }
    }
}
