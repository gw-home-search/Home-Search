package com.home.application.ingest;

import java.time.Instant;

public record RtmsIngestRunRecord(
	Long id,
	String lawdCd,
	String dealYmd,
	String status,
	int pageCount,
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long matchFailed,
	long parseFailed,
	String failureReason,
	Instant startedAt,
	Instant completedAt,
	Instant createdAt
) {

	public static RtmsIngestRunRecord completed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		Instant startedAt,
		Instant completedAt
	) {
		return new RtmsIngestRunRecord(
			null,
			lawdCd,
			dealYmd,
			"COMPLETED",
			pageCount,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			null,
			startedAt,
			completedAt,
			null
		);
	}
}
