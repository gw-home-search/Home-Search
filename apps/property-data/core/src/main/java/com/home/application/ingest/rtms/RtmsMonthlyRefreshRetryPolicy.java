package com.home.application.ingest.rtms;

public record RtmsMonthlyRefreshRetryPolicy(int maxAttempts, long backoffMillis) {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    public RtmsMonthlyRefreshRetryPolicy {
        maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
        backoffMillis = Math.max(backoffMillis, 0);
    }

    public static RtmsMonthlyRefreshRetryPolicy noBackoffDefault() {
        return new RtmsMonthlyRefreshRetryPolicy(DEFAULT_MAX_ATTEMPTS, 0);
    }
}
