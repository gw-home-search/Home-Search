package com.home.news.infrastructure.external.openai;

import com.fasterxml.jackson.databind.JsonNode;

public record SpringAiHistoricalNewsPrompt(
	String systemPrompt,
	String userPrompt,
	JsonNode responseSchema
) {
}
