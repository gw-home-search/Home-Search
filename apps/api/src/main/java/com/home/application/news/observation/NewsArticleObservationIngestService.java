package com.home.application.news.observation;

import java.util.List;
import java.util.Objects;

public class NewsArticleObservationIngestService {

	private final NewsArticleObservationRepository repository;

	public NewsArticleObservationIngestService(NewsArticleObservationRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public NewsArticleObservationIngestResult ingest(List<NewsArticleObservationCommand> commands) {
		if (commands == null || commands.isEmpty()) {
			return NewsArticleObservationIngestResult.empty();
		}

		long observed = 0;
		long duplicateSkipped = 0;
		for (NewsArticleObservationCommand command : commands) {
			if (repository.insertIfAbsent(command)) {
				observed++;
			}
			else {
				duplicateSkipped++;
			}
		}
		return new NewsArticleObservationIngestResult(commands.size(), observed, duplicateSkipped);
	}
}
