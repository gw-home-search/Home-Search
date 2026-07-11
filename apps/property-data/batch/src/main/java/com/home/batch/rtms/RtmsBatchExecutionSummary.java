package com.home.batch.rtms;

import java.util.List;

import com.home.application.ingest.rtms.RtmsMonthlyRefreshRunStatus;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshRunSummary;

public record RtmsBatchExecutionSummary(
	List<RtmsMonthlyRefreshRunSummary> runs,
	boolean regionSyncFailed
) {

	static final String WARNINGS_CONTEXT_KEY = "rtmsBatchWarnings";

	public RtmsBatchExecutionSummary {
		runs = runs == null ? List.of() : List.copyOf(runs);
	}

	boolean hasWarnings() {
		return regionSyncFailed || runs.stream().anyMatch(run -> run.status() != RtmsMonthlyRefreshRunStatus.COMPLETED);
	}
}
