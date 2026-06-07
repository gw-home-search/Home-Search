package com.home.application.news;

public record NewsArticleObservationCleanupRecord(
	long articleObservationId,
	String source,
	String sourceKey,
	String ingestStatus,
	String purgeAction
) {
}
