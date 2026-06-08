package com.home.application.ingest.run;

import java.util.List;

/**
 * Read-only RTMS ingest run report. Raw payload and source_key are intentionally excluded.
 */
public record RtmsIngestRunReport(
	RtmsIngestRunReportTotals totals,
	List<RtmsIngestRunStatusSummary> statusSummaries,
	List<RtmsIngestRunRecord> recentRuns
) {

	public RtmsIngestRunReport {
		if (totals == null) {
			throw new IllegalArgumentException("totals is required");
		}
		statusSummaries = statusSummaries == null ? List.of() : List.copyOf(statusSummaries);
		recentRuns = recentRuns == null ? List.of() : List.copyOf(recentRuns);
	}
}
