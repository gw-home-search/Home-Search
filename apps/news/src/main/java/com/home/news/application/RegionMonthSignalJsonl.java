package com.home.news.application;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalEvidenceScope;
import com.home.domain.news.RegionMonthSignalSourceKind;

public class RegionMonthSignalJsonl {

	private final ObjectMapper objectMapper;
	private final RegionMonthSignalValidator validator;

	public RegionMonthSignalJsonl(ObjectMapper objectMapper, RegionMonthSignalValidator validator) {
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	public List<RegionMonthSignalSnapshot> read(Path path) {
		List<RegionMonthSignalSnapshot> rows = new ArrayList<>();
		if (!Files.exists(path)) {
			return rows;
		}
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				try {
					JsonNode node = objectMapper.readTree(line);
					ForbiddenNewsTextGuard.rejectForbiddenJsonKeys(node);
					RegionMonthSignalSnapshot snapshot = fromJson(node);
					validator.validate(snapshot);
					rows.add(snapshot);
				}
				catch (Exception ex) {
					throw new NewsSignalValidationException(path + ":" + lineNumber + " invalid region-month signal row", ex);
				}
			}
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to read JSONL: " + path, ex);
		}
		return List.copyOf(rows);
	}

	public void write(Path path, List<RegionMonthSignalSnapshot> snapshots) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				for (RegionMonthSignalSnapshot snapshot : snapshots) {
					validator.validate(snapshot);
					writer.write(objectMapper.writeValueAsString(toJson(snapshot)));
					writer.newLine();
				}
			}
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to write JSONL: " + path, ex);
		}
	}

	private RegionMonthSignalSnapshot fromJson(JsonNode node) {
		List<RegionMonthSignalEvidence> evidence = new ArrayList<>();
		JsonNode evidenceNode = node.path("evidence");
		if (evidenceNode.isArray()) {
			for (JsonNode item : evidenceNode) {
				List<String> tags = new ArrayList<>();
				item.path("topic_tags").forEach(tag -> tags.add(tag.asText()));
				evidence.add(new RegionMonthSignalEvidence(
					requiredText(item, "source_key"),
					requiredText(item, "title"),
					requiredText(item, "publisher"),
					optionalDate(item, "published_date"),
					optionalText(item, "url"),
					optionalText(item, "citation_url"),
					List.copyOf(tags),
					RegionMonthSignalEvidenceScope.valueOf(requiredText(item, "evidence_scope"))
				));
			}
		}
		return new RegionMonthSignalSnapshot(
			NewsRegionBucket.valueOf(requiredText(node, "region_bucket")),
			YearMonth.parse(requiredText(node, "signal_month")).atDay(1),
			RegionMonthSignalSourceKind.valueOf(requiredText(node, "source_kind")),
			requiredText(node, "method_version"),
			NewsModelDatasetTier.valueOf(requiredText(node, "dataset_tier")),
			requiredInt(node, "news_count"),
			requiredInt(node, "matched_news_count"),
			requiredInt(node, "direct_evidence_count"),
			requiredInt(node, "inherited_evidence_count"),
			requiredInt(node, "policy_positive_score"),
			requiredInt(node, "policy_negative_score"),
			requiredInt(node, "redevelopment_score"),
			requiredInt(node, "transport_score"),
			requiredInt(node, "supply_risk_score"),
			requiredInt(node, "sale_market_score"),
			requiredInt(node, "rental_market_score"),
			requiredInt(node, "price_up_signal"),
			requiredInt(node, "price_down_signal"),
			requiredDecimal(node, "confidence"),
			requiredText(node, "aggregate_note"),
			List.copyOf(evidence)
		);
	}

	private Map<String, Object> toJson(RegionMonthSignalSnapshot snapshot) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("region_bucket", snapshot.regionBucket().name());
		row.put("signal_month", YearMonth.from(snapshot.signalMonth()).toString());
		row.put("source_kind", snapshot.sourceKind().name());
		row.put("method_version", snapshot.methodVersion());
		row.put("dataset_tier", snapshot.datasetTier().name());
		row.put("news_count", snapshot.newsCount());
		row.put("matched_news_count", snapshot.matchedNewsCount());
		row.put("direct_evidence_count", snapshot.directEvidenceCount());
		row.put("inherited_evidence_count", snapshot.inheritedEvidenceCount());
		row.put("policy_positive_score", snapshot.policyPositiveScore());
		row.put("policy_negative_score", snapshot.policyNegativeScore());
		row.put("redevelopment_score", snapshot.redevelopmentScore());
		row.put("transport_score", snapshot.transportScore());
		row.put("supply_risk_score", snapshot.supplyRiskScore());
		row.put("sale_market_score", snapshot.saleMarketScore());
		row.put("rental_market_score", snapshot.rentalMarketScore());
		row.put("price_up_signal", snapshot.priceUpSignal());
		row.put("price_down_signal", snapshot.priceDownSignal());
		row.put("confidence", snapshot.confidence());
		row.put("aggregate_note", snapshot.aggregateNote());
		row.put("evidence", snapshot.evidence().stream().map(this::toJson).toList());
		return row;
	}

	private Map<String, Object> toJson(RegionMonthSignalEvidence evidence) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("source_key", evidence.sourceKey());
		row.put("title", evidence.title());
		row.put("publisher", evidence.publisher());
		row.put("published_date", evidence.publishedDate() == null ? null : evidence.publishedDate().toString());
		row.put("url", evidence.url());
		row.put("citation_url", evidence.citationUrl());
		row.put("topic_tags", evidence.topicTags());
		row.put("evidence_scope", evidence.evidenceScope().name());
		return row;
	}

	private static String requiredText(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (value == null || value.isBlank()) {
			throw new NewsSignalValidationException(field + " is required");
		}
		return value;
	}

	private static String optionalText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		return value.asText();
	}

	private static LocalDate optionalDate(JsonNode node, String field) {
		String value = optionalText(node, field);
		return value == null || value.isBlank() ? null : LocalDate.parse(value);
	}

	private static int requiredInt(JsonNode node, String field) {
		if (!node.has(field)) {
			throw new NewsSignalValidationException(field + " is required");
		}
		return node.get(field).asInt();
	}

	private static BigDecimal requiredDecimal(JsonNode node, String field) {
		if (!node.has(field)) {
			throw new NewsSignalValidationException(field + " is required");
		}
		return node.get(field).decimalValue();
	}
}
