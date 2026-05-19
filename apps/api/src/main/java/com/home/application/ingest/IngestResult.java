package com.home.application.ingest;

public record IngestResult(
	long read,
	long rawSaved,
	long normalizedInserted,
	long duplicateSkipped,
	long matchFailed,
	long parseFailed
) {
}
