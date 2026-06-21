package com.home.news.infrastructure.external.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.ArticleObservationResult;
import com.home.news.application.NewsCollectionException;
import com.home.news.application.NewsSignalExtraction;
import com.home.news.application.NewsSignalScorer;

public class OpenAiNewsSignalScorer implements NewsSignalScorer {

	public static final String PROMPT_TEXT = """
		Classify a Korean real-estate news metadata item into prediction-safe structured labels.
		Use only the provided title and official snippet. Do not summarize the article.
		Return only JSON that matches the schema.
		""";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final NewsSignalStructuredOutputParser parser;
	private final NewsRuntimeProperties properties;

	public OpenAiNewsSignalScorer(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		NewsSignalStructuredOutputParser parser,
		NewsRuntimeProperties properties
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.parser = parser;
		this.properties = properties;
	}

	@Override
	public NewsSignalExtraction score(ArticleObservationResult observation) {
		validateConfiguration();
		try {
			String requestBody = objectMapper.writeValueAsString(requestJson(observation));
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(properties.getOpenai().getBaseUrl()))
				.header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new NewsCollectionException(OpenAiErrorDetails.failureMessage(
					"OpenAI scoring request",
					response.statusCode(),
					response.body(),
					response.headers(),
					objectMapper
				));
			}
			return parser.parse(extractOutputText(response.body()));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("OpenAI scoring request failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NewsCollectionException("OpenAI scoring request interrupted", ex);
		}
	}

	private ObjectNode requestJson(ArticleObservationResult observation) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", properties.getOpenai().getModel());
		root.put("store", false);
		ArrayNode input = root.putArray("input");
		input.add(message("system", PROMPT_TEXT));
		input.add(message("user", userInput(observation)));
		ObjectNode text = root.putObject("text");
		ObjectNode format = text.putObject("format");
		format.put("type", "json_schema");
		format.put("name", "news_signal_feature");
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

	private String userInput(ArticleObservationResult observation) {
		return """
			source=%s
			publisher=%s
			title=%s
			snippet=%s
			""".formatted(
			observation.source().name(),
			observation.publisher(),
			observation.title(),
			observation.snippet() == null ? "" : observation.snippet()
		);
	}

	private ObjectNode schema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");
		ObjectNode propertiesNode = schema.putObject("properties");
		propertiesNode.set("region_tags", arraySchema());
		propertiesNode.set("complex_candidates", arraySchema());
		propertiesNode.set("topic_tags", arraySchema());
		propertiesNode.set("impact_target", enumSchema("sale_price", "jeonse_price", "volume", "supply", "liquidity", "risk"));
		propertiesNode.set("impact_direction", enumSchema("up", "down", "mixed", "unknown"));
		propertiesNode.set("sentiment", enumSchema("positive", "neutral", "negative", "mixed"));
		ObjectNode confidence = propertiesNode.putObject("confidence");
		confidence.put("type", "number");
		confidence.put("minimum", 0);
		confidence.put("maximum", 1);
		propertiesNode.set("evidence_level", enumSchema("title", "snippet"));
		ArrayNode required = schema.putArray("required");
		required.add("region_tags");
		required.add("complex_candidates");
		required.add("topic_tags");
		required.add("impact_target");
		required.add("impact_direction");
		required.add("sentiment");
		required.add("confidence");
		required.add("evidence_level");
		schema.put("additionalProperties", false);
		return schema;
	}

	private ObjectNode arraySchema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "array");
		return schema;
	}

	private ObjectNode enumSchema(String... values) {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "string");
		ArrayNode enums = schema.putArray("enum");
		for (String value : values) {
			enums.add(value);
		}
		return schema;
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
			throw new NewsCollectionException("OpenAI response did not contain output_text");
		}
		catch (NewsCollectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new NewsCollectionException("OpenAI response parsing failed", ex);
		}
	}

	private void validateConfiguration() {
		if (!properties.getOpenai().isEnabled()) {
			throw new NewsCollectionException("OpenAI scoring is disabled");
		}
		if (properties.getOpenai().getApiKey() == null || properties.getOpenai().getApiKey().isBlank()) {
			throw new NewsCollectionException("OpenAI API key is required when news scoring is enabled");
		}
		if (properties.getOpenai().getModel() == null || properties.getOpenai().getModel().isBlank()) {
			throw new NewsCollectionException("home.news.openai.model is required when news scoring is enabled");
		}
	}
}
