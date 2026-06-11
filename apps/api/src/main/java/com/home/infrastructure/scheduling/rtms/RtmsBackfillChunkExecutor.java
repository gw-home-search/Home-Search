package com.home.infrastructure.scheduling.rtms;

import com.home.application.ingest.backfill.RtmsBackfillChunkRequest;

@FunctionalInterface
interface RtmsBackfillChunkExecutor {

	RtmsBackfillChunkExecutionResult execute(RtmsBackfillChunkRequest request);
}
