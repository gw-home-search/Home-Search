package com.home.infrastructure.external.naver;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NaverNewsSearchResponseParser {

	private final ObjectMapper objectMapper;

	NaverNewsSearchResponseParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	NaverNewsSearchPage parse(String payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			List<NaverNewsSearchItem> items = new ArrayList<>();
			for (JsonNode item : root.path("items")) {
				items.add(new NaverNewsSearchItem(
					text(item, "title"),
					text(item, "originallink"),
					text(item, "link"),
					text(item, "description"),
					text(item, "pubDate")
				));
			}
			return new NaverNewsSearchPage(
				parseDate(text(root, "lastBuildDate")),
				root.path("total").asLong(0),
				root.path("start").asInt(1),
				root.path("display").asInt(items.size()),
				items
			);
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Failed to parse Naver News search response", exception);
		}
	}

	static OffsetDateTime parseDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
	}

	private static String text(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		return value.asText();
	}
}
