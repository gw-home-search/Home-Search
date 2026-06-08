package com.home.application.news.signal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import com.home.application.news.relevance.NewsArticleRelevanceDecisionType;

public record NewsSignalFeatureExtractionCandidate(
	long articleObservationId,
	String source,
	String sourceKey,
	String publisher,
	String title,
	String snippet,
	LocalDate newsDateKst,
	OffsetDateTime firstSeenAt,
	NewsArticleRelevanceDecisionType relevanceDecisionType
) {
}
