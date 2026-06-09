package com.home.infrastructure.external.rtms;

import com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts;
import com.home.domain.ingest.backfill.RtmsBackfillJobStatus;

record RtmsNationwideBackfillReport(
	long jobId,
	RtmsBackfillJobStatus jobStatus,
	RtmsBackfillChunkStatusCounts statusCounts,
	int recoveredStaleCount
) {

	long completedCount() {
		return statusCounts.completed();
	}

	long failedCount() {
		return statusCounts.failed();
	}
}
