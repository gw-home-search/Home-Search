package com.home.application.news.relevance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NewsArticleRelevanceDecision(
	long articleObservationId,
	String source,
	String sourceKey,
	String policyVersion,
	NewsArticleRelevanceDecisionType decisionType,
	double score,
	double threshold,
	List<String> reasonCodes,
	Map<String, List<String>> matchedTerms,
	OffsetDateTime evaluatedAt
) {

	public NewsArticleRelevanceDecision {
		source = requireText(source, "source");
		sourceKey = requireText(sourceKey, "sourceKey");
		policyVersion = requireText(policyVersion, "policyVersion");
		decisionType = Objects.requireNonNull(decisionType, "decisionType must not be null");
		if (score < 0.0 || score > 1.0) {
			throw new IllegalArgumentException("score must be between 0 and 1");
		}
		if (threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("threshold must be between 0 and 1");
		}
		reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
		matchedTerms = Map.copyOf(matchedTerms == null ? Map.of() : matchedTerms);
		evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
	}

	public String skipFailureReason() {
		return "policyVersion=%s;decision=%s;score=%.4f;threshold=%.4f;reasonCodes=%s".formatted(
			policyVersion,
			decisionType.name(),
			score,
			threshold,
			reasonCodes
		);
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
