package com.home.application.prediction;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

public record PredictionProperties(
	boolean enabled,
	String modelVersion,
	Duration readyTtl,
	Duration pendingTtl,
	Duration failedTtl,
	Duration unavailableTtl,
	Duration lockTtl,
	BigDecimal intervalPct,
	String intervalBasis,
	ZoneId zoneId
) {

	public PredictionProperties {
		Objects.requireNonNull(readyTtl, "readyTtl must not be null");
		Objects.requireNonNull(pendingTtl, "pendingTtl must not be null");
		Objects.requireNonNull(failedTtl, "failedTtl must not be null");
		Objects.requireNonNull(unavailableTtl, "unavailableTtl must not be null");
		Objects.requireNonNull(lockTtl, "lockTtl must not be null");
		Objects.requireNonNull(intervalPct, "intervalPct must not be null");
		Objects.requireNonNull(zoneId, "zoneId must not be null");
		if (!isPositive(readyTtl) || !isPositive(pendingTtl) || !isPositive(failedTtl)
			|| !isPositive(unavailableTtl) || !isPositive(lockTtl)) {
			throw new IllegalArgumentException("prediction ttl values must be positive");
		}
	}

	private static boolean isPositive(Duration duration) {
		return !duration.isZero() && !duration.isNegative();
	}
}
