package com.home.news.infrastructure.external.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsRegionBucket;
import com.home.news.application.HistoricalNewsResearchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpringAiHistoricalNewsPromptFactoryTest {

	private final SpringAiHistoricalNewsPromptFactory factory = new SpringAiHistoricalNewsPromptFactory(new ObjectMapper());

	@Test
	@DisplayName("Spring AI prompt는 가격 예측 signal 후보 기준과 지역/topic profile을 렌더링한다")
	void rendersSignalFocusedPromptWithProfiles() {
		SpringAiHistoricalNewsPrompt prompt = factory.create(request());

		assertThat(prompt.systemPrompt())
			.contains("apartment price prediction signal dataset")
			.contains("Return only news that can become a measurable apartment market feature")
			.contains("If fewer than target candidates meet the standard, return fewer candidates")
			.contains("Do not return article body");

		assertThat(prompt.userPrompt())
			.contains("query_month: 2020-06")
			.contains("region_bucket: SEOUL_GANGNAM_GU")
			.contains("강남구")
			.contains("대치")
			.contains("url and url_citation must be direct http(s) URLs")
			.contains("Do not use citation labels")
			.contains("loan_rate")
			.contains("대출 규제")
			.contains("Pass 1")
			.contains("Pass 2")
			.contains("Pass 3")
			.contains("format:");
	}

	@Test
	@DisplayName("Spring AI DTO schema는 strict Responses JSON schema로 사용할 필드를 제공한다")
	void createsStrictSchemaFromDto() {
		SpringAiHistoricalNewsPrompt prompt = factory.create(request());

		String schema = prompt.responseSchema().toString();

		assertThat(schema)
			.contains("\"candidates\"")
			.contains("\"query_month\"")
			.contains("\"score_signal_strength\"")
			.contains("\"STRONG\"")
			.contains("\"model_utility\"")
			.contains("\"HIGH\"")
			.contains("\"additionalProperties\":false")
			.doesNotContain("article_summary")
			.doesNotContain("full_text");
	}

	private HistoricalNewsResearchRequest request() {
		return new HistoricalNewsResearchRequest(
			YearMonth.of(2020, 6),
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			5
		);
	}
}
