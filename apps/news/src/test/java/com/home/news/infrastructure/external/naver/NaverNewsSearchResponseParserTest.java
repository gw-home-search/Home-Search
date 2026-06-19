package com.home.news.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsSearchResponseParserTest {

	private final NaverNewsSearchResponseParser parser = new NaverNewsSearchResponseParser(new ObjectMapper());

	@Test
	@DisplayName("Naver News JSON은 HTML 제거, publisher/source_key/payload allowlist로 매핑된다")
	void mapsNaverNewsItem() {
		String response = """
			{
			  "total": 11,
			  "start": 1,
			  "display": 1,
			  "items": [
			    {
			      "title": "<b>강남</b> 재건축 &amp; 공급",
			      "originallink": "https://www.example.com/news/1",
			      "link": "https://n.news.naver.com/mnews/article/001/0000000001",
			      "description": "서울 <b>아파트</b> 정책",
			      "pubDate": "Tue, 14 Nov 2023 15:30:00 +0900"
			    }
			  ]
			}
			""";

		var first = parser.parse(response).articles().get(0);
		var second = parser.parse(response).articles().get(0);

		assertThat(first.title()).isEqualTo("강남 재건축 & 공급");
		assertThat(first.snippet()).isEqualTo("서울 아파트 정책");
		assertThat(first.publisher()).isEqualTo("example.com");
		assertThat(first.sourceKey()).isEqualTo(second.sourceKey());
		assertThat(first.rawProviderPayloadJson()).contains("provider_rank");
		assertThat(first.rawProviderPayloadJson()).doesNotContain("content");
		assertThat(first.rawProviderPayloadJson()).doesNotContain("body");
		assertThat(first.payloadHash()).matches("[0-9a-f]{64}");
	}
}
