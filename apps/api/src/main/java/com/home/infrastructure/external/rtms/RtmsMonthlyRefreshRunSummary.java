package com.home.infrastructure.external.rtms;

import com.home.application.ingest.IngestResult;

record RtmsMonthlyRefreshRunSummary(
	String lawdCd,
	String dealYmd,
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long matchFailed,
	long parseFailed,
	int pageCount,
	RtmsMonthlyRefreshRunStatus status
) {

	static RtmsMonthlyRefreshRunSummary completed(String lawdCd, String dealYmd, int pageCount, IngestResult result) {
		return new RtmsMonthlyRefreshRunSummary(
			lawdCd,
			dealYmd,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			pageCount,
			RtmsMonthlyRefreshRunStatus.COMPLETED
		);
	}

	boolean hasNewData() {
		return normalizedInserted > 0;
	}

	IngestResult ingestResult() {
		return new IngestResult(
			read,
			rawSaved,
			normalizedInserted,
			duplicateSkipped,
			matchFailed,
			parseFailed
		);
	}
}
