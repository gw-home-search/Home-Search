package com.home.application.news.collection;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsCollectionRepository {

	List<NewsCollectionKeyword> findDueKeywords(OffsetDateTime now, int limit);

	long startRun(OffsetDateTime startedAt);

	long startKeyword(long runId, NewsCollectionKeyword keyword, OffsetDateTime startedAt);

	void recordArticles(long runKeywordId, List<NewsCollectionArticleDiscovery> discoveries);

	void completeKeyword(NewsCollectionRunKeywordCompletion completion);

	void markKeywordCollected(long keywordId, OffsetDateTime collectedAt, OffsetDateTime nextDueAt);

	void completeRun(NewsCollectionRunCompletion completion);
}
