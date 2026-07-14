package com.home.application.ingest.rtms;

import java.time.Clock;
import java.util.Objects;

public record RtmsMonthlyRefreshExecution(Clock clock, RtmsMonthlyRefreshRetryPolicy retryPolicy) {

    public RtmsMonthlyRefreshExecution {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    }
}
