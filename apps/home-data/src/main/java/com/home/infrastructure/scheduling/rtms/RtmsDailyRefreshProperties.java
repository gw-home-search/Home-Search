package com.home.infrastructure.scheduling.rtms;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

record RtmsDailyRefreshProperties(
	List<String> lawdCds,
	int lookbackMonths,
	ZoneId zoneId
) {

	private static final int MAX_LOOKBACK_MONTHS = 24;

	RtmsDailyRefreshProperties {
		lawdCds = lawdCds == null ? List.of() : List.copyOf(lawdCds);
		if (lookbackMonths < 0) {
			throw new IllegalArgumentException("lookbackMonths must not be negative");
		}
		if (lookbackMonths > MAX_LOOKBACK_MONTHS) {
			throw new IllegalArgumentException("lookbackMonths must be less than or equal to 24");
		}
		zoneId = Objects.requireNonNull(zoneId, "zoneId is required");
	}

	static RtmsDailyRefreshProperties from(String lawdCds, int lookbackMonths, String zoneId) {
		return new RtmsDailyRefreshProperties(parseLawdCds(lawdCds), lookbackMonths, ZoneId.of(zoneId));
	}

	private static List<String> parseLawdCds(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(item -> !item.isBlank())
			.distinct()
			.toList();
	}
}
