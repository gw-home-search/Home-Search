package com.home.news.application;

import java.util.List;

public record NewsSearchResult(
	int providerTotal,
	int providerStart,
	int providerDisplay,
	List<NewsArticleMetadata> articles
) {
}
