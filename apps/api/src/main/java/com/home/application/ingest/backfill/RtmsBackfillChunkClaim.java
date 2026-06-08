package com.home.application.ingest.backfill;

public record RtmsBackfillChunkClaim(
	Long id,
	Long jobId,
	String lawdCd,
	String dealYmd,
	RtmsBackfillChunkStatus status,
	int attemptCount
) {

	public RtmsBackfillChunkRequest request() {
		return new RtmsBackfillChunkRequest(lawdCd, dealYmd);
	}
}
