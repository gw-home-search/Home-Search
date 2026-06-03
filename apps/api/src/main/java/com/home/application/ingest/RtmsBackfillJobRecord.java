package com.home.application.ingest;

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
