package com.home.infrastructure.scheduling.news;

import java.util.List;

import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.domain.news.NewsCollectionRunStatus;

record NaverNewsDailyPipelineExecution(
	long runId,
	NewsCollectionRunStatus status,
	List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions,
	NewsCollectionRunCompletion completion
) {

	NaverNewsDailyPipelineExecution {
		keywordExecutions = keywordExecutions == null ? List.of() : List.copyOf(keywordExecutions);
	}
}
