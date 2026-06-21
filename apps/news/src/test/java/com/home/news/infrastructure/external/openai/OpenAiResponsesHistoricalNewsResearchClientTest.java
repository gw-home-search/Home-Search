package com.home.news.infrastructure.external.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.YearMonth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.domain.news.NewsRegionBucket;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.HistoricalNewsResearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiResponsesHistoricalNewsResearchClientTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("historical research Responses request는 GPT-5.4 web_search와 strict schema를 사용한다")
	void buildsResponsesRequestWithWebSearchAndStrictSchema() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.getResearchSeed().setModel("gpt-5.4-2026-03-05");
		OpenAiResponsesHistoricalNewsResearchClient client = new OpenAiResponsesHistoricalNewsResearchClient(
			HttpClient.newHttpClient(),
			objectMapper,
			new HistoricalNewsResearchOutputParser(objectMapper),
			new SpringAiHistoricalNewsPromptFactory(objectMapper),
			properties
		);

		ObjectNode body = client.requestJson(new HistoricalNewsResearchRequest(
			YearMonth.of(2020, 6),
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			5
		));

		assertThat(body.path("model").asText()).isEqualTo("gpt-5.4-2026-03-05");
		assertThat(body.path("store").asBoolean()).isFalse();
		assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
		assertThat(body.path("tools").get(0).path("external_web_access").asBoolean()).isTrue();
		assertThat(body.path("tool_choice").asText()).isEqualTo("required");
		assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("medium");
		assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
		assertThat(body.path("text").path("format").path("schema").toString())
			.contains("\"query_month\"")
			.contains("\"score_signal_strength\"");
		assertThat(body.path("input").get(0).path("role").asText()).isEqualTo("system");
		assertThat(body.path("input").get(1).path("content").get(0).path("text").asText())
			.contains("query_month: 2020-06")
			.contains("Pass 3");
	}
}
