package com.home.application.ingest.run;

import java.time.Instant;
import com.home.application.ingest.trade.IngestResult;

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
	long canceledSkipped,
	long matchFailed,
	long parseFailed,
	String failureReason,
	Instant startedAt,
	Instant completedAt,
	Instant createdAt
) {

	public RtmsIngestRunRecord(
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
		this(
			id,
			lawdCd,
			dealYmd,
			status,
			pageCount,
			read,
			rawSaved,
			normalizedInserted,
			duplicateSkipped,
			0,
			matchFailed,
			parseFailed,
			failureReason,
			startedAt,
			completedAt,
			createdAt
		);
	}

	public static RtmsIngestRunRecord completed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		Instant startedAt,
		Instant completedAt
	) {
		return of(lawdCd, dealYmd, pageCount, result, "COMPLETED", null, startedAt, completedAt);
	}

	public static RtmsIngestRunRecord failed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason,
		Instant startedAt,
		Instant completedAt
	) {
		return of(lawdCd, dealYmd, pageCount, result, "FAILED", failureReason, startedAt, completedAt);
	}

	public static RtmsIngestRunRecord partiallyFailed(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String failureReason,
		Instant startedAt,
		Instant completedAt
	) {
		return of(lawdCd, dealYmd, pageCount, result, "PARTIAL", failureReason, startedAt, completedAt);
	}

	public static RtmsIngestRunRecord of(
		String lawdCd,
		String dealYmd,
		int pageCount,
		IngestResult result,
		String status,
		String failureReason,
		Instant startedAt,
		Instant completedAt
	) {
		return new RtmsIngestRunRecord(
			null,
			lawdCd,
			dealYmd,
			status,
			pageCount,
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.canceledSkipped(),
			result.matchFailed(),
			result.parseFailed(),
			failureReason,
			startedAt,
			completedAt,
			null
		);
	}
}
