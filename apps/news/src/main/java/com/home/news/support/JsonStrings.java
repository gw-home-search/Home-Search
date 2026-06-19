package com.home.news.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonStrings {

	private JsonStrings() {
	}

	public static String compact(ObjectMapper objectMapper, JsonNode node) {
		try {
			return objectMapper.writeValueAsString(node);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("JSON serialization failed", ex);
		}
	}
}
