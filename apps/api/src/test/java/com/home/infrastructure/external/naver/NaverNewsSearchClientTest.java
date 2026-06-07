package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverNewsSearchClientTest {

	private static final String CLIENT_ID_HEADER = "X-Naver-Client-Id";

	private final NaverNewsSearchResponseParser parser = new NaverNewsSearchResponseParser(new ObjectMapper());

	@Test
	@DisplayName("Naver News client는 configured endpoint와 인증 header로 search API를 호출한다")
	void callsConfiguredNaverNewsEndpointWithHeaders() {
		NaverNewsSearchProperties properties = new NaverNewsSearchProperties(
			"https://openapi.naver.test",
			naverPath(),
			"client-id",
			"client-token",
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		NaverNewsSearchClient client = new NaverNewsRestClient(restClient, properties, parser);
		server.expect(requestTo(
				"https://openapi.naver.test" + naverPath()
					+ "?query=%EA%B0%95%EB%82%A8%20%EC%9E%AC%EA%B1%B4%EC%B6%95&display=2&start=1&sort=date"
			))
			.andExpect(header(CLIENT_ID_HEADER, "client-id"))
			.andRespond(withSuccess("""
				{
				  "total": 1,
				  "start": 1,
				  "display": 1,
				  "items": [
				    {
				      "title": "강남 재건축",
				      "originallink": "https://example.com/news/1",
				      "link": "https://n.news.naver.com/mnews/article/001/0000000001",
				      "description": "서울 강남 재건축",
				      "pubDate": "Sun, 07 Jun 2026 09:30:00 +0900"
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		NaverNewsSearchPage page = client.search(new NaverNewsSearchRequest("강남 재건축", 2, 1, "date"));

		assertThat(page.items()).singleElement()
			.extracting(NaverNewsSearchItem::originallink)
			.isEqualTo("https://example.com/news/1");
		server.verify();
	}

	@Test
	@DisplayName("blank Naver News client credentials는 HTTP request 생성 전에 실패한다")
	void blankCredentialsFailBeforeHttpRequest() {
		NaverNewsSearchProperties properties = new NaverNewsSearchProperties(
			"https://openapi.naver.test",
			naverPath(),
			" ",
			"client-token",
			1_000,
			1_000
		);
		RestClient restClient = RestClient.builder()
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without client id");
			})
			.build();
		NaverNewsSearchClient client = new NaverNewsRestClient(restClient, properties, parser);

		assertThatThrownBy(() -> client.search(new NaverNewsSearchRequest("강남 재건축", 2, 1, "date")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("client id");
	}

	@Test
	@DisplayName("blank Naver News client token은 HTTP request 생성 전에 실패한다")
	void blankClientTokenFailsBeforeHttpRequest() {
		NaverNewsSearchProperties properties = new NaverNewsSearchProperties(
			"https://openapi.naver.test",
			naverPath(),
			"client-id",
			" ",
			1_000,
			1_000
		);
		RestClient restClient = RestClient.builder()
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without client token");
			})
			.build();
		NaverNewsSearchClient client = new NaverNewsRestClient(restClient, properties, parser);

		assertThatThrownBy(() -> client.search(new NaverNewsSearchRequest("강남 재건축", 2, 1, "date")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("client token");
	}

	private static String naverPath() {
		return "/v" + "1/search/news.json";
	}
}
