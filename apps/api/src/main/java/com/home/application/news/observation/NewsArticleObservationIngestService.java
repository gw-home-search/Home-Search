package com.home.application.news.observation;

import java.util.List;
import java.util.Objects;

public class NewsArticleObservationIngestService {

	private final NewsArticleObservationRepository repository;

	public NewsArticleObservationIngestService(NewsArticleObservationRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public NewsArticleObservationIngestResult ingest(List<NewsArticleObservationCommand> commands) {
		return ingestDetailed(commands).result();
	}

	public NewsArticleObservationDetailedIngestResult ingestDetailed(List<NewsArticleObservationCommand> commands) {
		if (commands == null || commands.isEmpty()) {
			return NewsArticleObservationDetailedIngestResult.empty();
		}

		long observed = 0;
		long duplicateSkipped = 0;
		var outcomes = new java.util.ArrayList<NewsArticleObservationDetailedIngestResult.ArticleOutcome>();
		for (NewsArticleObservationCommand command : commands) {
			boolean inserted = repository.insertIfAbsent(command);
			outcomes.add(new NewsArticleObservationDetailedIngestResult.ArticleOutcome(command, inserted));
			if (inserted) {
				observed++;
			}
			else {
				duplicateSkipped++;
			}
		}
		return new NewsArticleObservationDetailedIngestResult(
			new NewsArticleObservationIngestResult(commands.size(), observed, duplicateSkipped),
			outcomes
		);
	}
}
