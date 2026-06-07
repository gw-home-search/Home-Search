package com.home.application.news;

public record NewsArticleRelevanceGateResult(
	long evaluated,
	long kept,
	long reviewed,
	long skippedIrrelevant,
	long statusUpdated,
	long decisionDuplicateSkipped
) {

	public static NewsArticleRelevanceGateResult empty() {
		return new NewsArticleRelevanceGateResult(0, 0, 0, 0, 0, 0);
	}
}
