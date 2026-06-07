package com.home.application.news;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record NewsSignalFeatureCommand(
	long articleObservationId,
	String source,
	String sourceKey,
	LocalDate featureDateKst,
	OffsetDateTime firstSeenAt,
	List<String> regionTags,
	List<Map<String, Object>> complexCandidates,
	List<String> topicTags,
	String impactTarget,
	String impactDirection,
	String sentiment,
	double confidence,
	String extractionVersion,
	String evidenceLevel
) {

	public NewsSignalFeatureCommand {
		regionTags = List.copyOf(regionTags);
		complexCandidates = List.copyOf(complexCandidates);
		topicTags = List.copyOf(topicTags);
	}
}
