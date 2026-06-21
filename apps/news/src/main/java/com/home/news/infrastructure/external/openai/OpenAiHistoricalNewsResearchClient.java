package com.home.news.infrastructure.external.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchRequest;
import com.home.news.application.HistoricalNewsResearchResult;
import com.home.news.application.NewsCollectionException;

public class OpenAiHistoricalNewsResearchClient implements HistoricalNewsResearchClient {

	private static final String PROMPT_TEXT = """
		Find historically important Korean real-estate news candidates for model feature testing.
		Use web search citations. Return only metadata candidates that match the JSON schema.
		Do not include article body text or article-like summaries.
		Prefer original publisher or reputable source URLs with verifiable publication dates.
		""";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final HistoricalNewsResearchOutputParser parser;
	private final NewsRuntimeProperties properties;

	public OpenAiHistoricalNewsResearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		HistoricalNewsResearchOutputParser parser,
		NewsRuntimeProperties properties
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.parser = parser;
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
				throw new NewsCollectionException("OpenAI historical research request failed with status " + response.statusCode());
			}
			return parser.parse(extractOutputText(response.body()));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("OpenAI historical research request failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NewsCollectionException("OpenAI historical research request interrupted", ex);
		}
	}

	private ObjectNode requestJson(HistoricalNewsResearchRequest request) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", researchModel());
		root.put("store", false);
		ArrayNode tools = root.putArray("tools");
		ObjectNode webSearch = objectMapper.createObjectNode();
		webSearch.put("type", "web_search");
		tools.add(webSearch);
		root.put("tool_choice", "required");
		ArrayNode input = root.putArray("input");
		input.add(message("system", PROMPT_TEXT));
		input.add(message("user", userInput(request)));
		ObjectNode text = root.putObject("text");
		ObjectNode format = text.putObject("format");
		format.put("type", "json_schema");
		format.put("name", "historical_news_research_candidates");
		format.put("strict", true);
		format.set("schema", schema());
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

	private String userInput(HistoricalNewsResearchRequest request) {
		return """
			period_start=%s
			period_end=%s
			target_candidates_per_bucket=%d
			region_buckets=%s
			allowed_topics=%s
			""".formatted(
			request.periodStart(),
			request.periodEnd(),
			request.targetCandidatesPerBucket(),
			request.buckets().stream().map(NewsRegionBucket::name).collect(Collectors.joining(",")),
			java.util.Arrays.stream(NewsSignalTopic.values()).map(NewsSignalTopic::name).collect(Collectors.joining(","))
		);
	}

	private ObjectNode schema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode propertiesNode = schema.putObject("properties");
		ObjectNode candidates = propertiesNode.putObject("candidates");
		candidates.put("type", "array");
		candidates.set("items", candidateSchema());
		schema.putArray("required").add("candidates");
		schema.put("additionalProperties", false);
		return schema;
	}

	private ObjectNode candidateSchema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode propertiesNode = schema.putObject("properties");
		propertiesNode.set("title", stringSchema());
		propertiesNode.set("publisher", stringSchema());
		propertiesNode.set("published_date", stringSchema());
		propertiesNode.set("url", stringSchema());
		propertiesNode.set("url_citation", stringSchema());
		propertiesNode.set("region_bucket", enumSchema(enumNames(NewsRegionBucket.values())));
		propertiesNode.set("topic", enumSchema(enumNames(NewsSignalTopic.values())));
		propertiesNode.set("impact_target", enumSchema("sale_price", "jeonse_price", "volume", "supply", "liquidity", "risk"));
		propertiesNode.set("impact_direction_hint", enumSchema("up", "down", "mixed", "unknown"));
		propertiesNode.set("model_utility", stringSchema());
		ObjectNode confidence = propertiesNode.putObject("confidence");
		confidence.put("type", "number");
		confidence.put("minimum", 0);
		confidence.put("maximum", 1);
		ObjectNode reasonCodes = propertiesNode.putObject("reason_codes");
		reasonCodes.put("type", "array");
		reasonCodes.set("items", stringSchema());
		ArrayNode required = schema.putArray("required");
		required.add("title");
		required.add("publisher");
		required.add("published_date");
		required.add("url");
		required.add("url_citation");
		required.add("region_bucket");
		required.add("topic");
		required.add("impact_target");
		required.add("impact_direction_hint");
		required.add("model_utility");
		required.add("confidence");
		required.add("reason_codes");
		schema.put("additionalProperties", false);
		return schema;
	}

	private ObjectNode stringSchema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "string");
		return schema;
	}

	private ObjectNode enumSchema(String... values) {
		ObjectNode schema = stringSchema();
		ArrayNode enums = schema.putArray("enum");
		for (String value : values) {
			enums.add(value);
		}
		return schema;
	}

	private String[] enumNames(Enum<?>[] values) {
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = values[i].name();
		}
		return names;
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
}
