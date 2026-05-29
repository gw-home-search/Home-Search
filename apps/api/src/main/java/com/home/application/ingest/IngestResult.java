package com.home.application.ingest;

public record IngestResult(
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long canceledSkipped,
	long matchFailed,
	long parseFailed
) {

	public IngestResult(
		long read,
		long rawSaved,
		long normalizedInserted,
		long duplicateSkipped,
		long matchFailed,
		long parseFailed
	) {
		this(read, rawSaved, normalizedInserted, duplicateSkipped, 0, matchFailed, parseFailed);
	}

	public static IngestResult empty() {
		return new IngestResult(0, 0, 0, 0, 0, 0, 0);
	}

	public IngestResult plus(IngestResult other) {
		return new IngestResult(
			read + other.read(),
			rawSaved + other.rawSaved(),
			normalizedInserted + other.normalizedInserted(),
			duplicateSkipped + other.duplicateSkipped(),
			canceledSkipped + other.canceledSkipped(),
			matchFailed + other.matchFailed(),
			parseFailed + other.parseFailed()
		);
	}
}
