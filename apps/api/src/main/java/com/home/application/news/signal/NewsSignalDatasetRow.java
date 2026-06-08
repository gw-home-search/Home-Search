package com.home.application.news.signal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record NewsSignalDatasetRow(
	long featureId,
	long articleObservationId,
	String source,
	String sourceKey,
	String publisher,
	String title,
	String url,
	String providerUrl,
	String snippet,
	OffsetDateTime publishedAt,
	OffsetDateTime providerPubAt,
	OffsetDateTime firstSeenAt,
	LocalDate featureDateKst,
	LocalDate newsDateKst,
	OffsetDateTime articleCollectedAt,
	List<String> titleKeywords,
	List<String> regionTags,
	List<Map<String, Object>> complexCandidates,
	List<String> topicTags,
	String impactTarget,
	String impactDirection,
	String sentiment,
	double confidence,
	String extractionVersion,
	String evidenceLevel,
	OffsetDateTime featureCreatedAt
) {

	public NewsSignalDatasetRow {
		titleKeywords = List.copyOf(titleKeywords);
		regionTags = List.copyOf(regionTags);
		complexCandidates = List.copyOf(complexCandidates);
		topicTags = List.copyOf(topicTags);
	}
}
