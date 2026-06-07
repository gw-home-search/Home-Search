package com.home.application.news;

import java.time.LocalDate;
import java.time.OffsetDateTime;

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
