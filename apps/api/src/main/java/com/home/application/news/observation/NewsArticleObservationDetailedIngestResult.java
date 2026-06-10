package com.home.application.news.observation;

import java.util.List;
import java.util.Objects;

public record NewsArticleObservationDetailedIngestResult(
	NewsArticleObservationIngestResult result,
	List<ArticleOutcome> articleOutcomes
) {

	public NewsArticleObservationDetailedIngestResult {
		result = Objects.requireNonNull(result, "result must not be null");
		articleOutcomes = articleOutcomes == null ? List.of() : List.copyOf(articleOutcomes);
	}

	public record ArticleOutcome(NewsArticleObservationCommand command, boolean observed) {

		public ArticleOutcome {
			command = Objects.requireNonNull(command, "command must not be null");
		}
	}

	public static NewsArticleObservationDetailedIngestResult empty() {
		return new NewsArticleObservationDetailedIngestResult(NewsArticleObservationIngestResult.empty(), List.of());
	}
}
