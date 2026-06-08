package com.home.application.news.observation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public class NewsArticleObservationCleanupService {

	private final NewsArticleObservationCleanupRepository repository;

	public NewsArticleObservationCleanupService(NewsArticleObservationCleanupRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public NewsArticleObservationCleanupResult cleanup(OffsetDateTime retentionCutoff) {
		List<NewsArticleObservationCleanupRecord> records = repository.purgeProviderPayloads(
			Objects.requireNonNull(retentionCutoff)
		);
		return new NewsArticleObservationCleanupResult(records.size(), records);
	}
}
