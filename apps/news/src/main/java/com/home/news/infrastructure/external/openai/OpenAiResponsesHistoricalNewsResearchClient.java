package com.home.news.infrastructure.external.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.HistoricalNewsCandidate;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchRequest;
import com.home.news.application.HistoricalNewsResearchResult;
import com.home.news.application.NewsCollectionException;

public class OpenAiResponsesHistoricalNewsResearchClient implements HistoricalNewsResearchClient {

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final HistoricalNewsResearchOutputParser parser;
	private final SpringAiHistoricalNewsPromptFactory promptFactory;
	private final NewsRuntimeProperties properties;

	public OpenAiResponsesHistoricalNewsResearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		HistoricalNewsResearchOutputParser parser,
		SpringAiHistoricalNewsPromptFactory promptFactory,
		NewsRuntimeProperties properties
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.parser = parser;
		this.promptFactory = promptFactory;
		this.properties = properties;
	}

	@Override
	public HistoricalNewsResearchResult research(HistoricalNewsResearchRequest request) {
		validateConfiguration();
		try {
			String requestBody = objectMapper.writeValueAsString(requestJson(request));
			HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(properties.getOpenai().getBaseUrl()))
				.header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new NewsCollectionException(OpenAiErrorDetails.failureMessage(
					"OpenAI historical research request",
					response.statusCode(),
					response.body(),
					response.headers(),
					objectMapper
				));
			}
			return withQueryBucket(request, parser.parse(extractOutputText(response.body())));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("OpenAI historical research request failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NewsCollectionException("OpenAI historical research request interrupted", ex);
		}
	}

	ObjectNode requestJson(HistoricalNewsResearchRequest request) {
		SpringAiHistoricalNewsPrompt prompt = promptFactory.create(request);
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", researchModel());
		root.put("store", false);
		ArrayNode tools = root.putArray("tools");
		ObjectNode webSearch = objectMapper.createObjectNode();
		webSearch.put("type", "web_search");
		webSearch.put("external_web_access", true);
		tools.add(webSearch);
		root.put("tool_choice", "required");
		ObjectNode reasoning = root.putObject("reasoning");
		reasoning.put("effort", reasoningEffort());
		int maxOutputTokens = properties.getResearchSeed().getMaxOutputTokens();
		if (maxOutputTokens > 0) {
			root.put("max_output_tokens", maxOutputTokens);
		}
		int maxToolCalls = properties.getResearchSeed().getMaxToolCalls();
		if (maxToolCalls > 0) {
			root.put("max_tool_calls", maxToolCalls);
		}
		ArrayNode input = root.putArray("input");
		input.add(message("system", prompt.systemPrompt()));
		input.add(message("user", prompt.userPrompt()));
		ObjectNode text = root.putObject("text");
		ObjectNode format = text.putObject("format");
		format.put("type", "json_schema");
		format.put("name", "historical_news_research_candidates");
		format.put("strict", true);
		format.set("schema", prompt.responseSchema());
		return root;
	}

	private ObjectNode message(String role, String text) {
		ObjectNode message = objectMapper.createObjectNode();
		message.put("role", role);
		ArrayNode content = message.putArray("content");
		ObjectNode inputText = objectMapper.createObjectNode();
		inputText.put("type", "input_text");
		inputText.put("text", text);
		content.add(inputText);
		return message;
	}

	private HistoricalNewsResearchResult withQueryBucket(
		HistoricalNewsResearchRequest request,
		HistoricalNewsResearchResult result
	) {
		List<HistoricalNewsCandidate> candidates = result.candidates().stream()
			.map(candidate -> candidate.withQueryBucket(request.regionBucket()))
			.toList();
		return new HistoricalNewsResearchResult(candidates);
	}

	private String extractOutputText(String responseBody) {
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			for (JsonNode output : root.path("output")) {
				for (JsonNode content : output.path("content")) {
					if ("output_text".equals(content.path("type").asText())) {
						String text = content.path("text").asText();
						if (!text.isBlank()) {
							return text;
						}
					}
				}
			}
			throw new NewsCollectionException("OpenAI historical research response did not contain output_text");
		}
		catch (NewsCollectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new NewsCollectionException("OpenAI historical research response parsing failed", ex);
		}
	}

	private void validateConfiguration() {
		if (!properties.getResearchSeed().isEnabled()) {
			throw new NewsCollectionException("Historical news research seed is disabled");
		}
		if (properties.getOpenai().getApiKey() == null || properties.getOpenai().getApiKey().isBlank()) {
			throw new NewsCollectionException("OpenAI API key is required when historical news research seed is enabled");
		}
		if (researchModel().isBlank()) {
			throw new NewsCollectionException("home.news.research-seed.model is required when historical news research seed is enabled");
		}
	}

	private String researchModel() {
		if (properties.getResearchSeed().getModel() != null && !properties.getResearchSeed().getModel().isBlank()) {
			return properties.getResearchSeed().getModel();
		}
		return properties.getOpenai().getModel() == null ? "" : properties.getOpenai().getModel();
	}

	private String reasoningEffort() {
		String effort = properties.getResearchSeed().getReasoningEffort();
		return effort == null || effort.isBlank() ? "medium" : effort.strip();
	}
}
