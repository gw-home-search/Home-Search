package com.home.news.infrastructure.external.openai;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.SignalEvidenceLevel;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalSentiment;
import com.home.news.application.NewsCollectionException;
import com.home.news.application.NewsSignalExtraction;

public class NewsSignalStructuredOutputParser {

	private static final Set<String> REQUIRED_FIELDS = Set.of(
		"region_tags",
		"complex_candidates",
		"topic_tags",
		"impact_target",
		"impact_direction",
		"sentiment",
		"confidence",
		"evidence_level"
	);
	private static final Set<String> FORBIDDEN_FIELDS = Set.of(
		"summary",
		"article_summary",
		"content",
		"body",
		"full_text",
		"html"
	);

	private final ObjectMapper objectMapper;

	public NewsSignalStructuredOutputParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public NewsSignalExtraction parse(String outputJson) {
		try {
			JsonNode root = objectMapper.readTree(outputJson);
			validateObject(root);
			JsonNode regionTags = requireArray(root, "region_tags");
			JsonNode complexCandidates = requireArray(root, "complex_candidates");
			JsonNode topicTags = requireArray(root, "topic_tags");
			BigDecimal confidence = root.path("confidence").decimalValue();
			if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
				throw new NewsCollectionException("OpenAI structured output confidence must be between 0 and 1");
			}
			SignalEvidenceLevel evidenceLevel = enumValue(SignalEvidenceLevel.class, root.path("evidence_level").asText(), "evidence_level");
			if (!evidenceLevel.isSlice01Allowed()) {
				throw new NewsCollectionException("OpenAI structured output evidence_level must use title or snippet in Slice 01");
			}
			return new NewsSignalExtraction(
				regionTags,
				complexCandidates,
				topicTags,
				enumValue(SignalImpactTarget.class, root.path("impact_target").asText(), "impact_target"),
				enumValue(SignalImpactDirection.class, root.path("impact_direction").asText(), "impact_direction"),
				enumValue(SignalSentiment.class, root.path("sentiment").asText(), "sentiment"),
				confidence,
				evidenceLevel,
				root
			);
		}
		catch (NewsCollectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new NewsCollectionException("OpenAI structured output parsing failed", ex);
		}
	}

	private void validateObject(JsonNode root) {
		if (!root.isObject()) {
			throw new NewsCollectionException("OpenAI structured output must be a JSON object");
		}
		for (String field : REQUIRED_FIELDS) {
			if (!root.has(field)) {
				throw new NewsCollectionException("OpenAI structured output is missing field " + field);
			}
		}
		Iterator<String> fields = root.fieldNames();
		while (fields.hasNext()) {
			String field = fields.next();
			if (!REQUIRED_FIELDS.contains(field)) {
				throw new NewsCollectionException("OpenAI structured output has unsupported field " + field);
			}
			if (FORBIDDEN_FIELDS.contains(field)) {
				throw new NewsCollectionException("OpenAI structured output has forbidden field " + field);
			}
		}
	}

	private JsonNode requireArray(JsonNode root, String fieldName) {
		JsonNode value = root.path(fieldName);
		if (!value.isArray()) {
			throw new NewsCollectionException("OpenAI structured output field " + fieldName + " must be an array");
		}
		return value;
	}

	private <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String fieldName) {
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException ex) {
			throw new NewsCollectionException("OpenAI structured output field " + fieldName + " has invalid value " + value, ex);
		}
	}
}
