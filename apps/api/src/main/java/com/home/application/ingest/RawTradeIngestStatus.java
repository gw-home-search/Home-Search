package com.home.application.ingest;

public enum RawTradeIngestStatus {
	RECEIVED,
	NORMALIZED,
	DUPLICATE,
	MATCH_FAILED,
	PARSE_FAILED,
	SKIPPED_INVALID
}
