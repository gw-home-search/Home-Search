package com.home.news.infrastructure.external.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HistoricalNewsResearchOutputParserTest {

	private final HistoricalNewsResearchOutputParser parser = new HistoricalNewsResearchOutputParser(new ObjectMapper());

	@Test
	@DisplayName("valid historical research output은 candidate list로 변환된다")
	void parsesValidOutput() {
		var result = parser.parse(validJson());

		assertThat(result.candidates()).hasSize(1);
		assertThat(result.candidates().get(0).queryMonth().toString()).isEqualTo("2020-06");
		assertThat(result.candidates().get(0).regionBucket().name()).isEqualTo("SEOUL_GANGNAM_GU");
		assertThat(result.candidates().get(0).topic().name()).isEqualTo("policy_regulation");
		assertThat(result.candidates().get(0).scoreSignalStrength().name()).isEqualTo("STRONG");
	}

	@Test
	@DisplayName("unsupported field는 거부한다")
	void rejectsUnsupportedField() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("\"reason_codes\": [\"policy\"]", "\"reason_codes\": [\"policy\"], \"extra\": true")))
			.hasMessageContaining("unsupported field extra");
	}

	@Test
	@DisplayName("summary/body field는 거부한다")
	void rejectsForbiddenBodyLikeField() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("\"reason_codes\": [\"policy\"]", "\"reason_codes\": [\"policy\"], \"article_summary\": \"forbidden\"")))
			.hasMessageContaining("forbidden field article_summary");
	}

	@Test
	@DisplayName("confidence가 숫자가 아니면 거부한다")
	void rejectsNonNumericConfidence() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("\"confidence\": 0.870", "\"confidence\": \"0.870\"")))
			.hasMessageContaining("confidence must be numeric");
	}

	@Test
	@DisplayName("candidate enum field가 지원되지 않으면 거부한다")
	void rejectsUnsupportedEnum() {
		assertThatThrownBy(() -> parser.parse(validJson().replace("\"score_signal_strength\": \"STRONG\"", "\"score_signal_strength\": \"MAYBE\"")))
			.hasMessageContaining("score_signal_strength");
	}

	private String validJson() {
		return """
			{
			  "candidates": [
			    {
			      "title": "강남 재건축 규제 완화",
			      "publisher": "Example Daily",
			      "published_date": "2020-06-02",
			      "url": "https://example.com/article",
			      "url_citation": "https://example.com/article",
			      "query_month": "2020-06",
			      "region_bucket": "SEOUL_GANGNAM_GU",
			      "topic": "policy_regulation",
			      "impact_target": "sale_price",
			      "impact_direction_hint": "up",
			      "score_signal_strength": "STRONG",
			      "model_utility": "HIGH",
			      "confidence": 0.870,
			      "reason_codes": ["policy"]
			    }
			  ]
			}
			""";
	}
}
