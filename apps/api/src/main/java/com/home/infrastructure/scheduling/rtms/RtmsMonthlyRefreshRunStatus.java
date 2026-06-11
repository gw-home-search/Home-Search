package com.home.infrastructure.scheduling.rtms;

import com.home.domain.ingest.backfill.RtmsBackfillChunkStatus;

enum RtmsMonthlyRefreshRunStatus {

	COMPLETED("COMPLETED", RtmsBackfillChunkStatus.COMPLETED, false),
	PARTIAL("PARTIAL", RtmsBackfillChunkStatus.PARTIAL, true),
	FAILED("FAILED", RtmsBackfillChunkStatus.FAILED, true);

	private final String storedValue;
	private final RtmsBackfillChunkStatus backfillStatus;
	private final boolean failure;

	RtmsMonthlyRefreshRunStatus(String storedValue, RtmsBackfillChunkStatus backfillStatus, boolean failure) {
		this.storedValue = storedValue;
		this.backfillStatus = backfillStatus;
		this.failure = failure;
	}

	String storedValue() {
		return storedValue;
	}

	RtmsBackfillChunkStatus backfillStatus() {
		return backfillStatus;
	}

	String failureReason(String failureReason) {
		return failure ? failureReason : null;
	}
}
