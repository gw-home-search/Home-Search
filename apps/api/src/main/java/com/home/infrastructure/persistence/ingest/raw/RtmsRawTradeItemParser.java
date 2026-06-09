package com.home.infrastructure.persistence.ingest.raw;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeItemParser;

public class RtmsRawTradeItemParser implements RawTradeItemParser {

	private final ObjectMapper objectMapper;

	public RtmsRawTradeItemParser(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper);
	}

	@Override
	public Optional<OpenApiTradeItem> parse(RawTradeIngestRecord raw) {
		if (raw == null || raw.payload() == null || raw.payload().isBlank()) {
			return Optional.empty();
		}
		try {
			JsonNode node = objectMapper.readTree(raw.payload());
			return Optional.of(new OpenApiTradeItem(
				text(node, "aptDong"),
				firstText(node, "aptNm", "aptName"),
				text(node, "aptSeq"),
				text(node, "dealAmount"),
				integer(node, "dealDay"),
				integer(node, "dealMonth"),
				integer(node, "dealYear"),
				decimal(node, "excluUseAr", "exclArea"),
				integer(node, "floor"),
				text(node, "jibun"),
				text(node, "sggCd"),
				text(node, "umdCd"),
				raw.payload(),
				text(node, "cdealType"),
				text(node, "cdealDay"),
				text(node, "rgstDate")
			));
		}
		catch (IOException | IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private String firstText(JsonNode node, String first, String second) {
		String value = text(node, first);
		return value == null ? text(node, second) : value;
	}

	private String text(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text.trim();
	}

	private Integer integer(JsonNode node, String fieldName) {
		String value = text(node, fieldName);
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private Double decimal(JsonNode node, String firstFieldName, String secondFieldName) {
		String value = firstText(node, firstFieldName, secondFieldName);
		if (value == null) {
			return null;
		}
		try {
			return Double.valueOf(value);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}
}
