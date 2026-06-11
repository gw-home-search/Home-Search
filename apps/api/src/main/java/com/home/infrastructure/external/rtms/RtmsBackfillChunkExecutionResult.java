package com.home.infrastructure.external.rtms;

import com.home.application.ingest.trade.IngestResult;
import com.home.domain.ingest.backfill.RtmsBackfillChunkStatus;

record RtmsBackfillChunkExecutionResult(
	String lawdCd,
	String dealYmd,
	Long runId,
	RtmsBackfillChunkStatus status,
	String failureReason,
	IngestResult result
) {

	static RtmsBackfillChunkExecutionResult completed(
		String lawdCd,
		String dealYmd,
		Long runId,
		IngestResult result
	) {
		return new RtmsBackfillChunkExecutionResult(
			lawdCd,
			dealYmd,
			runId,
			RtmsBackfillChunkStatus.COMPLETED,
			null,
			result
		);
	}

	static RtmsBackfillChunkExecutionResult failed(
		String lawdCd,
		String dealYmd,
		Long runId,
		String failureReason,
		IngestResult result
	) {
		return new RtmsBackfillChunkExecutionResult(
			lawdCd,
			dealYmd,
			runId,
			RtmsBackfillChunkStatus.FAILED,
			failureReason,
			result
		);
	}

	static RtmsBackfillChunkExecutionResult partial(
		String lawdCd,
		String dealYmd,
		Long runId,
		String failureReason,
		IngestResult result
	) {
		return new RtmsBackfillChunkExecutionResult(
			lawdCd,
			dealYmd,
			runId,
			RtmsBackfillChunkStatus.PARTIAL,
			failureReason,
			result
		);
	}

	static RtmsBackfillChunkExecutionResult from(RtmsMonthlyRefreshRunSummary summary) {
		return new RtmsBackfillChunkExecutionResult(
			summary.lawdCd(),
			summary.dealYmd(),
			summary.runId(),
			summary.status().backfillStatus(),
			summary.status().failureReason(summary.failureReason()),
			summary.ingestResult()
		);
	}
}
