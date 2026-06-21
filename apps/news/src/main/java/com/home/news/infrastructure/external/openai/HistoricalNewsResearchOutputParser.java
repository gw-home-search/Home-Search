package com.home.news.infrastructure.external.openai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.news.application.HistoricalNewsCandidate;
import com.home.news.application.HistoricalNewsResearchResult;
import com.home.news.application.NewsCollectionException;

public class HistoricalNewsResearchOutputParser {

	private static final Set<String> ROOT_FIELDS = Set.of("candidates");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
		"title",
		"publisher",
		"published_date",
		"url",
		"url_citation",
		"region_bucket",
		"topic",
		"impact_target",
		"impact_direction_hint",
		"model_utility",
		"confidence",
		"reason_codes"
	);
	private static final Set<String> FORBIDDEN_FIELDS = Set.of(
		"summary",
		"article_summary",
		"content",
		"body",
		"full_text",
		"html",
		"article_html",
		"본문",
		"내용",
		"원문",
		"기사본문"
	);

	private final ObjectMapper objectMapper;

	public HistoricalNewsResearchOutputParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public HistoricalNewsResearchResult parse(String outputJson) {
		try {
			JsonNode root = objectMapper.readTree(outputJson);
			validateObject(root, ROOT_FIELDS, "OpenAI research output");
			JsonNode candidatesNode = root.path("candidates");
			if (!candidatesNode.isArray()) {
				throw new NewsCollectionException("OpenAI research output candidates must be an array");
			}
			List<HistoricalNewsCandidate> candidates = new ArrayList<>();
			for (JsonNode candidateNode : candidatesNode) {
				candidates.add(candidate(candidateNode));
			}
			return new HistoricalNewsResearchResult(List.copyOf(candidates));
		}
		catch (NewsCollectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new NewsCollectionException("OpenAI research output parsing failed", ex);
		}
	}

	private HistoricalNewsCandidate candidate(JsonNode candidateNode) {
		validateObject(candidateNode, CANDIDATE_FIELDS, "OpenAI research candidate");
		JsonNode confidenceNode = candidateNode.path("confidence");
		if (!confidenceNode.isNumber()) {
			throw new NewsCollectionException("OpenAI research candidate confidence must be numeric");
		}
		BigDecimal confidence = confidenceNode.decimalValue();
		if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
			throw new NewsCollectionException("OpenAI research candidate confidence must be between 0 and 1");
		}
		return new HistoricalNewsCandidate(
			requiredText(candidateNode, "title"),
			requiredText(candidateNode, "publisher"),
			LocalDate.parse(requiredText(candidateNode, "published_date")),
			requiredText(candidateNode, "url"),
			candidateNode.path("url_citation").asText(""),
			enumValue(NewsRegionBucket.class, requiredText(candidateNode, "region_bucket"), "region_bucket"),
			enumValue(NewsSignalTopic.class, requiredText(candidateNode, "topic"), "topic"),
			enumValue(SignalImpactTarget.class, requiredText(candidateNode, "impact_target"), "impact_target"),
			enumValue(SignalImpactDirection.class, requiredText(candidateNode, "impact_direction_hint"), "impact_direction_hint"),
			requiredText(candidateNode, "model_utility"),
			confidence,
			reasonCodes(candidateNode.path("reason_codes"))
		);
	}

	private void validateObject(JsonNode node, Set<String> allowedFields, String label) {
		if (!node.isObject()) {
			throw new NewsCollectionException(label + " must be a JSON object");
		}
		Iterator<String> fields = node.fieldNames();
		while (fields.hasNext()) {
			String field = fields.next();
			if (FORBIDDEN_FIELDS.contains(field)) {
				throw new NewsCollectionException(label + " has forbidden field " + field);
			}
			if (!allowedFields.contains(field)) {
				throw new NewsCollectionException(label + " has unsupported field " + field);
			}
		}
		for (String field : allowedFields) {
			if (!node.has(field)) {
				throw new NewsCollectionException(label + " is missing field " + field);
			}
		}
	}

	private String requiredText(JsonNode node, String fieldName) {
		String value = node.path(fieldName).asText("");
		if (value.isBlank()) {
			throw new NewsCollectionException("OpenAI research candidate is missing field " + fieldName);
		}
		return value.strip();
	}

	private List<String> reasonCodes(JsonNode node) {
		if (!node.isArray()) {
			throw new NewsCollectionException("OpenAI research candidate reason_codes must be an array");
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isTextual() || item.asText().isBlank()) {
				throw new NewsCollectionException("OpenAI research candidate reason_codes must contain strings");
			}
			values.add(item.asText().strip());
		}
		return List.copyOf(values);
	}

	private <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String fieldName) {
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException ex) {
			throw new NewsCollectionException("OpenAI research candidate field " + fieldName + " has invalid value " + value, ex);
		}
	}
}
