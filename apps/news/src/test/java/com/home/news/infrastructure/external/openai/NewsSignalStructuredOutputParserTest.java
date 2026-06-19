package com.home.news.infrastructure.external.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsSignalStructuredOutputParserTest {

	private final NewsSignalStructuredOutputParser parser = new NewsSignalStructuredOutputParser(new ObjectMapper());

	@Test
	@DisplayName("valid structured JSON은 signal extraction으로 변환된다")
	void parsesValidStructuredOutput() {
		var extraction = parser.parse(validJson());

		assertThat(extraction.impactTarget().name()).isEqualTo("sale_price");
		assertThat(extraction.impactDirection().name()).isEqualTo("up");
		assertThat(extraction.confidence()).isEqualByComparingTo("0.875");
	}

	@Test
	@DisplayName("invalid enum 값은 거부한다")
	void rejectsInvalidEnum() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("\"sale_price\"", "\"bad\"")))
			.hasMessageContaining("impact_target");
	}

	@Test
	@DisplayName("extra field는 거부한다")
	void rejectsExtraField() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("}", ",\"summary\":\"forbidden\"}")))
			.hasMessageContaining("unsupported field");
	}

	@Test
	@DisplayName("missing field는 거부한다")
	void rejectsMissingField() {
		assertThatThrownBy(() -> parser.parse("""
			{
			  "region_tags": [],
			  "complex_candidates": [],
			  "topic_tags": [],
			  "impact_target": "sale_price",
			  "impact_direction": "up",
			  "sentiment": "positive",
			  "confidence": 0.875
			}
			""")).hasMessageContaining("missing field evidence_level");
	}

	@Test
	@DisplayName("confidence 범위 밖 값은 거부한다")
	void rejectsConfidenceOutOfRange() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("0.875", "1.2")))
			.hasMessageContaining("confidence");
	}

	private String validJson() {
		return """
			{
			  "region_tags": ["서울"],
			  "complex_candidates": [],
			  "topic_tags": ["policy"],
			  "impact_target": "sale_price",
			  "impact_direction": "up",
			  "sentiment": "positive",
			  "confidence": 0.875,
			  "evidence_level": "snippet"
			}
			""";
	}
}
