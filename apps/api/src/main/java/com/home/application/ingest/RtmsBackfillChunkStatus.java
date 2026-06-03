package com.home.application.ingest;

public enum RtmsBackfillChunkStatus {

	PENDING,
	RUNNING,
	COMPLETED,
	PARTIAL,
	FAILED,
	BLOCKED,
	SKIPPED
}
