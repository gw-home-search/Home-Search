package com.home.application.ingest.backfill;

import com.home.domain.ingest.backfill.RtmsBackfillJobStatus;

public record RtmsBackfillJobRecord(
	Long id,
	String jobKey,
	String source,
	String dealYmdFrom,
	String dealYmdTo,
	String lawdCodeSource,
	RtmsBackfillJobStatus status
) {
}
