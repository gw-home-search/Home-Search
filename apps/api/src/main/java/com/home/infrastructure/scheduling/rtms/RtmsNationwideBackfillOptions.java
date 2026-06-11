package com.home.infrastructure.scheduling.rtms;

import java.time.Duration;

record RtmsNationwideBackfillOptions(
	String workerId,
	Duration leaseDuration,
	int maxAttemptCount,
	int chunkLimit
) {

	RtmsNationwideBackfillOptions(String workerId, Duration leaseDuration, int maxAttemptCount) {
		this(workerId, leaseDuration, maxAttemptCount, Integer.MAX_VALUE);
	}

	RtmsNationwideBackfillOptions {
		if (workerId == null || workerId.isBlank()) {
			throw new IllegalArgumentException("workerId is required");
		}
		if (leaseDuration == null || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("leaseDuration must be zero or positive");
		}
		if (maxAttemptCount <= 0) {
			throw new IllegalArgumentException("maxAttemptCount must be positive");
		}
		if (chunkLimit <= 0) {
			throw new IllegalArgumentException("chunkLimit must be positive");
		}
	}
}
