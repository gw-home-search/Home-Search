package com.home.news.application;

import java.time.LocalDate;
import java.time.YearMonth;

import com.home.domain.news.NewsRegionBucket;

public record HistoricalNewsResearchRequest(
	YearMonth queryMonth,
	NewsRegionBucket regionBucket,
	int targetCandidates
) {

	public LocalDate monthStart() {
		return queryMonth.atDay(1);
	}

	public LocalDate monthEnd() {
		return queryMonth.atEndOfMonth();
	}
}
