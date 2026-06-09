package com.home.application.ingest.backfill;

import com.home.domain.ingest.backfill.RtmsBackfillChunkStatus;

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
