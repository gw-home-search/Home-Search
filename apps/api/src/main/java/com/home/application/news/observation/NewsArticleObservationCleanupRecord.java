package com.home.application.news.observation;

public record NewsArticleObservationCleanupRecord(
	long articleObservationId,
	String source,
	String sourceKey,
	String ingestStatus,
	String purgeAction
) {
}
