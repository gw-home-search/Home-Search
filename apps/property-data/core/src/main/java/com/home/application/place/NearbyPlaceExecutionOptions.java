package com.home.application.place;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

public record NearbyPlaceExecutionOptions(Executor executor, Clock clock, Duration totalTimeout) {

    public NearbyPlaceExecutionOptions {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(totalTimeout, "totalTimeout must not be null");
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("nearby place timeout must be positive");
        }
    }
}
