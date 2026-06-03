package com.home.infrastructure.external.rtms;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.RtmsBackfillChunkStatus;

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
}
