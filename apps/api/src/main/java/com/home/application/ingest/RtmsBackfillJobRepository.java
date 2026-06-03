package com.home.application.ingest;

public interface RtmsBackfillJobRepository {

	RtmsBackfillJobRecord createIfAbsent(
		String jobKey,
		String source,
		String dealYmdFrom,
		String dealYmdTo,
		String lawdCodeSource,
		int totalChunkCount
	);

	void markRunning(long jobId);

	void markCompleted(long jobId);

	void markPartial(long jobId, String failureReason);
}
