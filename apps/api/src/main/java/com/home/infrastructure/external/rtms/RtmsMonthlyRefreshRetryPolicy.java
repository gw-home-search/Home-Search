package com.home.infrastructure.external.rtms;

record RtmsMonthlyRefreshRetryPolicy(
	int maxAttempts,
	long backoffMillis
) {

	private static final int DEFAULT_MAX_ATTEMPTS = 3;

	RtmsMonthlyRefreshRetryPolicy {
		maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
		backoffMillis = Math.max(backoffMillis, 0);
	}

	static RtmsMonthlyRefreshRetryPolicy noBackoffDefault() {
		return new RtmsMonthlyRefreshRetryPolicy(DEFAULT_MAX_ATTEMPTS, 0);
	}
}
