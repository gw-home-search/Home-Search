package com.home.application.prediction;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;

public record PredictionExecutionContext(Executor executor, Clock clock) {

    public PredictionExecutionContext {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
    }
}
