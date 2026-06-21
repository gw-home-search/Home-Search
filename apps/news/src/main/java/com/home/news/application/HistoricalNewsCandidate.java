package com.home.news.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;

public record HistoricalNewsCandidate(
	String title,
	String publisher,
	LocalDate publishedDate,
	String url,
	String urlCitation,
	NewsRegionBucket regionBucket,
	NewsSignalTopic topic,
	SignalImpactTarget impactTarget,
	SignalImpactDirection impactDirectionHint,
	String modelUtility,
	BigDecimal confidence,
	List<String> reasonCodes
) {

	public boolean hasCitation() {
		return urlCitation != null && !urlCitation.isBlank();
	}
}
