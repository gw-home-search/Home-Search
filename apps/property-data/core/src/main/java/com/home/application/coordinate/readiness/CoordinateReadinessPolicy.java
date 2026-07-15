package com.home.application.coordinate.readiness;

import java.time.Duration;
import java.util.Objects;

public record CoordinateReadinessPolicy(int retryLimit, Duration retryAfter) {

    public CoordinateReadinessPolicy {
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        if (retryLimit < 0) {
            throw new IllegalArgumentException("retryLimit must be non-negative");
        }
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must be non-negative");
        }
    }
}
