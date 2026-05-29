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
	RtmsMonthlyRefreshRunStatus status,
	String failureReason
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
			RtmsMonthlyRefreshRunStatus.COMPLETED,
			null
		);
	}

	static RtmsMonthlyRefreshRunSummary failed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason
	) {
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
			RtmsMonthlyRefreshRunStatus.FAILED,
			failureReason
		);
	}

	static RtmsMonthlyRefreshRunSummary partiallyFailed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason
	) {
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
			RtmsMonthlyRefreshRunStatus.PARTIAL,
			failureReason
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
