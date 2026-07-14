package com.home.domain.ingest.run;

import java.util.Objects;
import java.util.UUID;

/** Persisted identifier that correlates one Spring Batch execution with its RTMS ingest evidence. */
public record ExecutionCorrelationId(UUID value) {

    public ExecutionCorrelationId {
        Objects.requireNonNull(value, "value is required");
    }

    public static ExecutionCorrelationId from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("execution correlation id is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("execution correlation id must be a canonical UUID");
            }
            return new ExecutionCorrelationId(parsed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("execution correlation id must be a canonical UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
