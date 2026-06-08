package com.home.infrastructure.external.rtms;

import com.home.application.ingest.trade.IngestResult;

record RtmsMonthlyRefreshRunSummary(
	String lawdCd,
	String dealYmd,
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long canceledSkipped,
	long matchFailed,
	long parseFailed,
	int pageCount,
	RtmsMonthlyRefreshRunStatus status,
	String failureReason,
	Long runId
) {

	RtmsMonthlyRefreshRunSummary(
		String lawdCd,
		String dealYmd,
		long read,
		long rawSaved,
		long normalizedInserted,
		long duplicateSkipped,
		long canceledSkipped,
		long matchFailed,
		long parseFailed,
		int pageCount,
		RtmsMonthlyRefreshRunStatus status,
		String failureReason
	) {
		this(
			lawdCd,
			dealYmd,
			read,
			rawSaved,
			normalizedInserted,
			duplicateSkipped,
			canceledSkipped,
			matchFailed,
			parseFailed,
			pageCount,
			status,
			failureReason,
			null
		);
	}

	RtmsMonthlyRefreshRunSummary(
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
		this(
			lawdCd,
			dealYmd,
			read,
			rawSaved,
			normalizedInserted,
			duplicateSkipped,
			0,
			matchFailed,
			parseFailed,
			pageCount,
			status,
			failureReason,
			null
		);
	}

	static RtmsMonthlyRefreshRunSummary completed(String lawdCd, String dealYmd, int pageCount, IngestResult result) {
		return completed(lawdCd, dealYmd, pageCount, result, null);
	}

	static RtmsMonthlyRefreshRunSummary completed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		Long runId
	) {
		return new RtmsMonthlyRefreshRunSummary(
			lawdCd,
			dealYmd,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.canceledSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			pageCount,
			RtmsMonthlyRefreshRunStatus.COMPLETED,
			null,
			runId
		);
	}

	static RtmsMonthlyRefreshRunSummary failed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason
	) {
		return failed(lawdCd, dealYmd, pageCount, result, failureReason, null);
	}

	static RtmsMonthlyRefreshRunSummary failed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason,
		Long runId
	) {
		return new RtmsMonthlyRefreshRunSummary(
			lawdCd,
			dealYmd,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.canceledSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			pageCount,
			RtmsMonthlyRefreshRunStatus.FAILED,
			failureReason,
			runId
		);
	}

	static RtmsMonthlyRefreshRunSummary partiallyFailed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason
	) {
		return partiallyFailed(lawdCd, dealYmd, pageCount, result, failureReason, null);
	}

	static RtmsMonthlyRefreshRunSummary partiallyFailed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason,
		Long runId
	) {
		return new RtmsMonthlyRefreshRunSummary(
			lawdCd,
			dealYmd,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.canceledSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			pageCount,
			RtmsMonthlyRefreshRunStatus.PARTIAL,
			failureReason,
			runId
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
			canceledSkipped,
			matchFailed,
			parseFailed
		);
	}
}
