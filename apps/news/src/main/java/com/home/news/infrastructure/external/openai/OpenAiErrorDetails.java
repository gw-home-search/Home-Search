package com.home.news.infrastructure.external.openai;

import java.net.http.HttpHeaders;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class OpenAiErrorDetails {

	private static final int MAX_VALUE_LENGTH = 240;

	private OpenAiErrorDetails() {
	}

	static String failureMessage(
		String operation,
		int statusCode,
		String responseBody,
		HttpHeaders headers,
		ObjectMapper objectMapper
	) {
		StringBuilder message = new StringBuilder(operation)
			.append(" failed with status ")
			.append(statusCode);
		appendErrorBody(message, responseBody, objectMapper);
		requestId(headers).ifPresent(requestId -> message.append(" request_id=").append(requestId));
		return message.toString();
	}

	private static void appendErrorBody(StringBuilder message, String responseBody, ObjectMapper objectMapper) {
		if (responseBody == null || responseBody.isBlank()) {
			return;
		}
		try {
			JsonNode error = objectMapper.readTree(responseBody).path("error");
			appendField(message, "type", error.path("type").asText(""));
			appendField(message, "code", error.path("code").asText(""));
			appendField(message, "message", error.path("message").asText(""));
		}
		catch (Exception ex) {
			message.append(" error_body=unparseable");
		}
	}

	private static Optional<String> requestId(HttpHeaders headers) {
		return headers.firstValue("x-request-id")
			.or(() -> headers.firstValue("request-id"))
			.map(OpenAiErrorDetails::sanitize)
			.filter(value -> !value.isBlank());
	}

	private static void appendField(StringBuilder message, String field, String value) {
		String sanitized = sanitize(value);
		if (!sanitized.isBlank() && !"null".equalsIgnoreCase(sanitized)) {
			message.append(' ').append(field).append('=').append(sanitized);
		}
	}

	private static String sanitize(String value) {
		String sanitized = value == null ? "" : value
			.replaceAll("\\p{Cntrl}+", " ")
			.replaceAll("\\s+", " ")
			.strip();
		if (sanitized.length() <= MAX_VALUE_LENGTH) {
			return sanitized;
		}
		return sanitized.substring(0, MAX_VALUE_LENGTH) + "...";
	}
}
