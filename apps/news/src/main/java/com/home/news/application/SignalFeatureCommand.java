package com.home.news.application;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.NewsSource;

public record SignalFeatureCommand(
	long articleObservationId,
	NewsSource source,
	String sourceKey,
	LocalDate featureDateKst,
	Instant firstSeenAt,
	String regionTagsJson,
	String complexCandidatesJson,
	String topicTagsJson,
	String impactTarget,
	String impactDirection,
	String sentiment,
	String confidence,
	String extractionVersion,
	String evidenceLevel,
	String model,
	String promptVersion,
	String inputHash,
	String structuredOutputJson
) {
}
