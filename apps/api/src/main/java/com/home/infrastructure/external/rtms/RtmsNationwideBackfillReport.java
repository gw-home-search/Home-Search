package com.home.infrastructure.external.rtms;

import com.home.application.ingest.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.RtmsBackfillJobStatus;

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
