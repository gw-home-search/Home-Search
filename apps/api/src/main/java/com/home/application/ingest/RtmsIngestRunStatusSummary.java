package com.home.application.ingest;

/**
 * Run count grouped by RTMS ingest run status.
 */
public record RtmsIngestRunStatusSummary(
	RtmsIngestRunStatus status,
	long runCount
) {

	public RtmsIngestRunStatusSummary {
		if (status == null) {
			throw new IllegalArgumentException("status is required");
		}
		if (runCount < 0) {
			throw new IllegalArgumentException("runCount must be non-negative");
		}
	}
}
