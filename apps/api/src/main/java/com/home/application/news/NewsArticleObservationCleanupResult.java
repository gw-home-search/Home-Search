package com.home.application.news;

import java.util.List;

public record NewsArticleObservationCleanupResult(
	long purged,
	List<NewsArticleObservationCleanupRecord> records
) {

	public NewsArticleObservationCleanupResult {
		records = List.copyOf(records);
	}
}
