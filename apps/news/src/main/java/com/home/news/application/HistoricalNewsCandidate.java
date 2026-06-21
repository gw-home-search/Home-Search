package com.home.news.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalModelUtility;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalScoreSignalStrength;

public record HistoricalNewsCandidate(
	String title,
	String publisher,
	LocalDate publishedDate,
	String url,
	String urlCitation,
	YearMonth queryMonth,
	NewsRegionBucket queryBucket,
	NewsRegionBucket regionBucket,
	NewsSignalTopic topic,
	SignalImpactTarget impactTarget,
	SignalImpactDirection impactDirectionHint,
	SignalScoreSignalStrength scoreSignalStrength,
	SignalModelUtility modelUtility,
	BigDecimal confidence,
	List<String> reasonCodes
) {

	public boolean hasCitation() {
		return urlCitation != null && !urlCitation.isBlank();
	}

	public HistoricalNewsCandidate withQueryBucket(NewsRegionBucket queryBucket) {
		return new HistoricalNewsCandidate(
			title,
			publisher,
			publishedDate,
			url,
			urlCitation,
			queryMonth,
			queryBucket,
			regionBucket,
			topic,
			impactTarget,
			impactDirectionHint,
			scoreSignalStrength,
			modelUtility,
			confidence,
			reasonCodes
		);
	}

	public HistoricalNewsCandidate withTitle(String title) {
		return new HistoricalNewsCandidate(
			title,
			publisher,
			publishedDate,
			url,
			urlCitation,
			queryMonth,
			queryBucket,
			regionBucket,
			topic,
			impactTarget,
			impactDirectionHint,
			scoreSignalStrength,
			modelUtility,
			confidence,
			reasonCodes
		);
	}

	public HistoricalNewsCandidate withUrl(String url) {
		return new HistoricalNewsCandidate(
			title,
			publisher,
			publishedDate,
			url,
			urlCitation,
			queryMonth,
			queryBucket,
			regionBucket,
			topic,
			impactTarget,
			impactDirectionHint,
			scoreSignalStrength,
			modelUtility,
			confidence,
			reasonCodes
		);
	}

	public HistoricalNewsCandidate withConfidence(BigDecimal confidence) {
		return new HistoricalNewsCandidate(
			title,
			publisher,
			publishedDate,
			url,
			urlCitation,
			queryMonth,
			queryBucket,
			regionBucket,
			topic,
			impactTarget,
			impactDirectionHint,
			scoreSignalStrength,
			modelUtility,
			confidence,
			reasonCodes
		);
	}

	public HistoricalNewsCandidate withScoreSignalStrength(SignalScoreSignalStrength scoreSignalStrength) {
		return new HistoricalNewsCandidate(
			title,
			publisher,
			publishedDate,
			url,
			urlCitation,
			queryMonth,
			queryBucket,
			regionBucket,
			topic,
			impactTarget,
			impactDirectionHint,
			scoreSignalStrength,
			modelUtility,
			confidence,
			reasonCodes
		);
	}
}
