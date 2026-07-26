package com.home.infrastructure.external.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.news.collection.NewsProviderQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class NaverNewsApiClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://naverapihub.test");
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final NaverNewsApiClient client =
            new NaverNewsApiClient(builder.build(), new ObjectMapper(), "/search/v1/news");

    @Test
    @DisplayName("display=100, sort=date 요청과 raw 원천 필드를 보존한다")
    void preservesRawProviderFields() {
        server.expect(request -> {
                    assertThat(request.getURI().getRawQuery())
                            .contains("query=%EC%95%84%ED%8C%8C%ED%8A%B8", "display=100", "start=1", "sort=date");
                })
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                            {
                              "total": 1,
                              "start": 1,
                              "display": 1,
                              "items": [{
                                "title": "<b>아파트</b> 정책",
                                "originallink": "https://news.example.test/1",
                                "link": "https://n.news.naver.com/1",
                                "description": "부동산 정책",
                                "pubDate": "Fri, 24 Jul 2026 15:00:00 +0900"
                              }]
                            }
                            """, org.springframework.http.MediaType.APPLICATION_JSON));

        var page = client.search(new NewsProviderQuery("아파트", 1, 100));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("<b>아파트</b> 정책");
            assertThat(item.providerStart()).isEqualTo(1);
            assertThat(item.providerRank()).isEqualTo(1);
        });
        server.verify();
    }

    @Test
    @DisplayName("401과 429를 retry 불가 인증·일일 quota 오류로 구분한다")
    void mapsAuthenticationAndQuotaErrors() {
        server.expect(request -> {})
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.search(new NewsProviderQuery("아파트", 1, 100)))
                .isInstanceOfSatisfying(
                        NaverNewsProviderException.class,
                        error -> assertThat(error.kind()).isEqualTo(NaverNewsFailureKind.AUTHENTICATION));
        server.verify();

        server.reset();
        server.expect(request -> {})
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                                HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60")
                        .body("{\"errorCode\":\"SE99\",\"errorMessage\":\"quota\"}"));

        assertThatThrownBy(() -> client.search(new NewsProviderQuery("아파트", 1, 100)))
                .isInstanceOfSatisfying(NaverNewsProviderException.class, error -> {
                    assertThat(error.kind()).isEqualTo(NaverNewsFailureKind.DAILY_QUOTA);
                    assertThat(error.retryAfterSeconds()).isEqualTo(60);
                });
        server.verify();
    }
}
