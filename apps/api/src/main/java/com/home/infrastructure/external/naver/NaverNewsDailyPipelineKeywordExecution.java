package com.home.infrastructure.external.naver;

import com.home.application.news.collection.NewsCollectionKeyword;
import com.home.application.news.observation.NewsArticleObservationIngestResult;
import com.home.domain.news.NewsCollectionRunStatus;

record NaverNewsDailyPipelineKeywordExecution(
	NewsCollectionKeyword keyword,
	NewsCollectionRunStatus status,
	NewsArticleObservationIngestResult ingestResult,
	String failureReason
) {

	static NaverNewsDailyPipelineKeywordExecution completed(
		NewsCollectionKeyword keyword,
		NewsArticleObservationIngestResult ingestResult
	) {
		return new NaverNewsDailyPipelineKeywordExecution(
			keyword,
			NewsCollectionRunStatus.COMPLETED,
			ingestResult,
			null
		);
	}

	static NaverNewsDailyPipelineKeywordExecution failed(NewsCollectionKeyword keyword, RuntimeException exception) {
		return new NaverNewsDailyPipelineKeywordExecution(
			keyword,
			NewsCollectionRunStatus.FAILED,
			NewsArticleObservationIngestResult.empty(),
			NaverNewsDailyPipelineMessageFormatter.sanitizeSensitiveValues(failureReason(exception))
		);
	}

	private static String failureReason(RuntimeException exception) {
		String reason = exception.getClass().getSimpleName();
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			reason = reason + ": " + exception.getMessage();
		}
		return reason;
	}
}
