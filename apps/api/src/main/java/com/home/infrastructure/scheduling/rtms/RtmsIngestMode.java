package com.home.infrastructure.scheduling.rtms;

enum RtmsIngestMode {

	ONE_SHOT,
	MONTHLY_REFRESH,
	NATIONWIDE_BACKFILL;

	static RtmsIngestMode from(String value) {
		if (value == null || value.isBlank()) {
			return ONE_SHOT;
		}
		String normalized = value.trim().replace("-", "_").toUpperCase();
		try {
			return RtmsIngestMode.valueOf(normalized);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
				"home.ingest.rtms.mode must be one-shot, monthly-refresh, or nationwide-backfill",
				exception
			);
		}
	}
}
