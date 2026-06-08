package com.home.infrastructure.external.rtms;

import com.home.application.ingest.backfill.RtmsBackfillChunkRequest;

@FunctionalInterface
interface RtmsBackfillChunkExecutor {

	RtmsBackfillChunkExecutionResult execute(RtmsBackfillChunkRequest request);
}
