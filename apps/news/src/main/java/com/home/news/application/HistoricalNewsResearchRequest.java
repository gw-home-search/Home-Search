package com.home.news.application;

import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;

public record HistoricalNewsResearchRequest(
	LocalDate periodStart,
	LocalDate periodEnd,
	List<NewsRegionBucket> buckets,
	int targetCandidatesPerBucket
) {
}
