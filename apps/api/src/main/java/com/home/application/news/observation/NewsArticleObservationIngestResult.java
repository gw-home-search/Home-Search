package com.home.application.news.observation;

public record NewsArticleObservationIngestResult(long read, long observed, long duplicateSkipped) {

	public static NewsArticleObservationIngestResult empty() {
		return new NewsArticleObservationIngestResult(0, 0, 0);
	}
}
