package com.home.news.infrastructure.external.openai;

import java.net.http.HttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.NewsRuntimeProperties;

public class OpenAiHistoricalNewsResearchClient extends OpenAiResponsesHistoricalNewsResearchClient {

	public OpenAiHistoricalNewsResearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		HistoricalNewsResearchOutputParser parser,
		SpringAiHistoricalNewsPromptFactory promptFactory,
		NewsRuntimeProperties properties
	) {
		super(httpClient, objectMapper, parser, promptFactory, properties);
	}
}
