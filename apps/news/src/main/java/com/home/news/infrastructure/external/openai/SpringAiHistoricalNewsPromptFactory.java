package com.home.news.infrastructure.external.openai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.news.application.HistoricalNewsResearchRequest;
import com.home.news.application.NewsCollectionException;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;

public class SpringAiHistoricalNewsPromptFactory {

	private static final String SYSTEM_TEMPLATE = "prompts/news/research-seed-system-v2-gpt54.st";
	private static final String USER_TEMPLATE = "prompts/news/research-seed-user-v2-gpt54.st";
	private static final String QUALITY_RULES = "prompts/news/research-seed-quality-rules-v1.st";

	private final ObjectMapper objectMapper;
	private final BeanOutputConverter<HistoricalNewsResearchResponseDto> outputConverter;

	public SpringAiHistoricalNewsPromptFactory(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.outputConverter = new BeanOutputConverter<>(HistoricalNewsResearchResponseDto.class);
	}

	public SpringAiHistoricalNewsPrompt create(HistoricalNewsResearchRequest request) {
		RegionSearchProfile regionProfile = RegionSearchProfile.forBucket(request.regionBucket());
		Map<String, Object> systemVariables = Map.of(
			"quality_rules", resourceText(QUALITY_RULES)
		);
		Map<String, Object> userVariables = Map.ofEntries(
			Map.entry("query_month", request.queryMonth().toString()),
			Map.entry("month_start", request.monthStart().toString()),
			Map.entry("month_end", request.monthEnd().toString()),
			Map.entry("region_bucket", request.regionBucket().name()),
			Map.entry("region_bucket_ko", regionProfile.nameKo()),
			Map.entry("region_aliases", regionProfile.aliasesText()),
			Map.entry("target_candidates", request.targetCandidates()),
			Map.entry("topic_search_profiles", topicSearchProfiles()),
			Map.entry("accepted_signal_examples", acceptedSignalExamples()),
			Map.entry("reject_rules", rejectRules()),
			Map.entry("format", "strict Responses JSON schema")
		);
		return new SpringAiHistoricalNewsPrompt(
			render(SYSTEM_TEMPLATE, systemVariables),
			render(USER_TEMPLATE, userVariables),
			responseSchema()
		);
	}

	private String render(String resourcePath, Map<String, Object> variables) {
		return new PromptTemplate(resourceText(resourcePath)).render(variables);
	}

	private String resourceText(String resourcePath) {
		try {
			return new String(new ClassPathResource(resourcePath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new NewsCollectionException("historical news prompt resource read failed: " + resourcePath, ex);
		}
	}

	private String topicSearchProfiles() {
		return TopicSearchProfile.all().stream()
			.map(TopicSearchProfile::line)
			.collect(Collectors.joining("\n"));
	}

	private String acceptedSignalExamples() {
		return String.join("\n",
			"- Pass: 강남구 토지거래허가구역 지정",
			"- Pass: 대출규제 강화",
			"- Pass: GTX 정차 or 개통 확정",
			"- Pass: 대규모 입주 물량 발표"
		);
	}

	private String rejectRules() {
		return String.join("\n",
			"- Reject: 강남 아파트 인기 여전",
			"- Reject: 분양 상담 광고",
			"- Reject: 전문가 전망만 있는 칼럼",
			"- Reject: 지역명만 언급된 전국 기사"
		);
	}

	private JsonNode responseSchema() {
		try {
			ObjectNode schema = (ObjectNode) objectMapper.readTree(outputConverter.getJsonSchema());
			enforceStrictObjectSchema(schema);
			return schema;
		}
		catch (IOException ex) {
			throw new NewsCollectionException("historical news response schema generation failed", ex);
		}
	}

	private void enforceStrictObjectSchema(JsonNode node) {
		if (!node.isObject()) {
			return;
		}
		ObjectNode objectNode = (ObjectNode) node;
		if ("object".equals(objectNode.path("type").asText()) && objectNode.path("properties").isObject()) {
			objectNode.put("additionalProperties", false);
			ArrayNode required = objectNode.putArray("required");
			Iterator<String> fields = objectNode.path("properties").fieldNames();
			while (fields.hasNext()) {
				required.add(fields.next());
			}
		}
		Iterator<JsonNode> children = objectNode.elements();
		while (children.hasNext()) {
			enforceStrictObjectSchema(children.next());
		}
	}
}
