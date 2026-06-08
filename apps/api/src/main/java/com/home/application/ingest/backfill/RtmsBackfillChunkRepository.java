package com.home.application.ingest.backfill;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface RtmsBackfillChunkRepository {

	int insertChunks(long jobId, List<RtmsBackfillChunkRequest> chunks, int maxAttemptCount);

	int recoverStaleRunning(long jobId, String failureReason);

	Optional<RtmsBackfillChunkClaim> claimNextRunnable(long jobId, String workerId, Duration leaseDuration);

	void markCompleted(long chunkId, Long runId);

	void markFailed(long chunkId, Long runId, String failureReason);

	void markPartial(long chunkId, Long runId, String failureReason);

	void markBlocked(long chunkId, String failureReason);

	Optional<RtmsBackfillChunkRecord> findById(long chunkId);

	RtmsBackfillChunkStatusCounts countStatuses(long jobId);
}
