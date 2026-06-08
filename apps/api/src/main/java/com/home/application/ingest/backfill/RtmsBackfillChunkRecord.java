package com.home.application.ingest.backfill;

public record RtmsBackfillChunkRecord(
	Long id,
	Long jobId,
	String lawdCd,
	String dealYmd,
	RtmsBackfillChunkStatus status,
	int attemptCount,
	int maxAttemptCount,
	Long lastRunId,
	String lastFailureReason
) {

	public RtmsBackfillChunkRecord withStatus(RtmsBackfillChunkStatus value) {
		return new RtmsBackfillChunkRecord(
			id,
			jobId,
			lawdCd,
			dealYmd,
			value,
			attemptCount,
			maxAttemptCount,
			lastRunId,
			lastFailureReason
		);
	}

	public RtmsBackfillChunkRecord withAttemptCount(int value) {
		return new RtmsBackfillChunkRecord(
			id,
			jobId,
			lawdCd,
			dealYmd,
			status,
			value,
			maxAttemptCount,
			lastRunId,
			lastFailureReason
		);
	}

	public RtmsBackfillChunkRecord withLastRunId(Long value) {
		return new RtmsBackfillChunkRecord(
			id,
			jobId,
			lawdCd,
			dealYmd,
			status,
			attemptCount,
			maxAttemptCount,
			value,
			lastFailureReason
		);
	}

	public RtmsBackfillChunkRecord withLastFailureReason(String value) {
		return new RtmsBackfillChunkRecord(
			id,
			jobId,
			lawdCd,
			dealYmd,
			status,
			attemptCount,
			maxAttemptCount,
			lastRunId,
			value
		);
	}
}
