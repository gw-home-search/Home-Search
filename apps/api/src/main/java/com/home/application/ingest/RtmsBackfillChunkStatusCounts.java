package com.home.application.ingest;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public record RtmsBackfillChunkStatusCounts(
	long pending,
	long running,
	long completed,
	long partial,
	long failed,
	long blocked,
	long skipped
) {

	public static RtmsBackfillChunkStatusCounts empty() {
		return new RtmsBackfillChunkStatusCounts(0, 0, 0, 0, 0, 0, 0);
	}

	public static RtmsBackfillChunkStatusCounts from(Collection<RtmsBackfillChunkRecord> chunks) {
		Map<RtmsBackfillChunkStatus, Long> counts = new EnumMap<>(RtmsBackfillChunkStatus.class);
		for (RtmsBackfillChunkRecord chunk : chunks) {
			counts.merge(chunk.status(), 1L, Long::sum);
		}
		return new RtmsBackfillChunkStatusCounts(
			counts.getOrDefault(RtmsBackfillChunkStatus.PENDING, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.RUNNING, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.COMPLETED, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.PARTIAL, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.FAILED, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.BLOCKED, 0L),
			counts.getOrDefault(RtmsBackfillChunkStatus.SKIPPED, 0L)
		);
	}

	public long terminalSuccessCount() {
		return completed + skipped;
	}

	public long problemCount() {
		return partial + failed + blocked;
	}
}
