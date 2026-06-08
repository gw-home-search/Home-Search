package com.home.application.news.observation;

import java.util.List;

public record NewsArticleObservationCleanupResult(
	long purged,
	List<NewsArticleObservationCleanupRecord> records
) {

	public NewsArticleObservationCleanupResult {
		records = List.copyOf(records);
	}
}
