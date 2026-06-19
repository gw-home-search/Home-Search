package com.home.news.application;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.home.domain.news.SignalEvidenceLevel;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalSentiment;

public record NewsSignalExtraction(
	JsonNode regionTags,
	JsonNode complexCandidates,
	JsonNode topicTags,
	SignalImpactTarget impactTarget,
	SignalImpactDirection impactDirection,
	SignalSentiment sentiment,
	BigDecimal confidence,
	SignalEvidenceLevel evidenceLevel,
	JsonNode structuredOutput
) {
}
