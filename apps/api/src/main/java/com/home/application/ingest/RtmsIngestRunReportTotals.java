package com.home.application.ingest;

/**
 * Total counts for stored RTMS ingest run outcomes.
 */
public record RtmsIngestRunReportTotals(
	long runCount,
	long pageCount,
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long canceledSkipped,
	long matchFailed,
	long parseFailed
) {

	public RtmsIngestRunReportTotals {
		requireNonNegative(runCount, "runCount");
		requireNonNegative(pageCount, "pageCount");
		requireNonNegative(read, "read");
		requireNonNegative(rawSaved, "rawSaved");
		requireNonNegative(normalizedInserted, "normalizedInserted");
		requireNonNegative(duplicateSkipped, "duplicateSkipped");
		requireNonNegative(canceledSkipped, "canceledSkipped");
		requireNonNegative(matchFailed, "matchFailed");
		requireNonNegative(parseFailed, "parseFailed");
	}

	private static void requireNonNegative(long value, String fieldName) {
		if (value < 0) {
			throw new IllegalArgumentException(fieldName + " must be non-negative");
		}
	}
}
