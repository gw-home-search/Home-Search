package com.home.infrastructure.external.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.NearbyPlaceBoundsArea;
import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProviderQuery;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.domain.place.NearbyPlaceCategory;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class KakaoNearbyPlaceProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-13T03:00:00Z");

    @Test
    @DisplayName("Kakao category API 요청과 응답을 provider-neutral 장소 결과로 변환한다")
    void mapsKakaoCategoryResponse() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK test-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("category_group_code", "CE7"))
                .andExpect(queryParam("x", "127.109"))
                .andExpect(queryParam("y", "37.321"))
                .andExpect(queryParam("radius", "800"))
                .andExpect(queryParam("sort", "distance"))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParam("size", "15"))
                .andRespond(withSuccess("""
				{
				  "meta": {"total_count": 18, "pageable_count": 18, "is_end": false},
				  "documents": [{
				    "id": "123456",
				    "place_name": "카페 이름",
				    "category_name": "음식점 > 카페",
				    "phone": "031-000-0000",
				    "address_name": "경기도 수원시",
				    "road_address_name": "경기도 수원시 도로명",
				    "x": "127.108",
				    "y": "37.322",
				    "place_url": "https://place.map.kakao.com/123456",
				    "distance": "72"
				  }]
				}
				""", MediaType.APPLICATION_JSON));

        KakaoNearbyPlaceProvider provider =
                new KakaoNearbyPlaceProvider(builder.build(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var result = provider.search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE);

        assertThat(result.matchedCount()).isEqualTo(18);
        assertThat(result.retrievedAt()).isEqualTo(NOW);
        assertThat(result.places()).singleElement().satisfies(place -> {
            assertThat(place.placeId()).isEqualTo("kakao:123456");
            assertThat(place.name()).isEqualTo("카페 이름");
            assertThat(place.lat()).isEqualTo(37.322);
            assertThat(place.lng()).isEqualTo(127.108);
            assertThat(place.distanceMeters()).isEqualTo(72);
            assertThat(place.placeUrl()).isEqualTo("https://place.map.kakao.com/123456");
        });
        server.verify();
    }

    @Test
    @DisplayName("viewport 조회는 Kakao에 중심 x/y와 rect, 단일 category만 전달한다")
    void sendsBoundsQuery() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET))
                .andExpect(queryParam("category_group_code", "CE7"))
                .andExpect(queryParam("x", "126.9"))
                .andExpect(queryParam("y", "37.48"))
                .andExpect(queryParam("rect", "126.85,37.45,126.95,37.51"))
                .andExpect(queryParam("sort", "distance"))
                .andExpect(queryParam("size", "15"))
                .andRespond(withSuccess("{\"meta\":{\"total_count\":0},\"documents\":[]}", MediaType.APPLICATION_JSON));
        KakaoNearbyPlaceProvider provider = provider(builder);
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.51, 126.95);

        var result = provider.search(new NearbyPlaceProviderQuery(
                new NearbyPlaceBoundsArea(new NearbyPlacePoint(37.48, 126.9), bounds, 4), NearbyPlaceCategory.CAFE));

        assertThat(result.category()).isEqualTo(NearbyPlaceCategory.CAFE);
        assertThat(result.places()).isEmpty();
        server.verify();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"SUPERMARKET, MT1", "DAYCARE_KINDERGARTEN, PS3", "ACADEMY, AC5", "SUBWAY_STATION, SW8"})
    @DisplayName("신규 주변시설 category를 Kakao category group code로 변환한다")
    void mapsNewFacilityCategoryCodes(NearbyPlaceCategory category, String kakaoCode) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET))
                .andExpect(queryParam("category_group_code", kakaoCode))
                .andRespond(withSuccess("{\"meta\":{\"total_count\":0},\"documents\":[]}", MediaType.APPLICATION_JSON));

        provider(builder).search(new NearbyPlacePoint(37.321, 127.109), 800, category);

        server.verify();
    }

    @Test
    @DisplayName("Kakao response가 허용 크기를 넘으면 원문을 노출하지 않고 실패한다")
    void rejectsOversizedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET)).andRespond(withSuccess("x".repeat(1_048_577), MediaType.APPLICATION_JSON));
        KakaoNearbyPlaceProvider provider =
                new KakaoNearbyPlaceProvider(builder.build(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> provider.search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageNotContaining("xxxx");
    }

    @Test
    @DisplayName("Kakao 4xx와 5xx는 partial 장소 결과 없이 provider 실패로 변환한다")
    void rejectsProviderErrorStatuses() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.SERVICE_UNAVAILABLE}) {
            RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(once(), method(GET)).andRespond(withStatus(status));
            KakaoNearbyPlaceProvider provider = provider(builder);

            assertThatThrownBy(
                            () -> provider.search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE))
                    .isInstanceOf(NearbyPlaceProviderUnavailableException.class);
            server.verify();
        }
    }

    @Test
    @DisplayName("Kakao timeout은 credential이나 provider 원문 없이 실패한다")
    void convertsTimeoutToProviderFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET)).andRespond(request -> {
            throw new SocketTimeoutException("provider-secret-timeout-detail");
        });
        KakaoNearbyPlaceProvider provider = provider(builder);

        assertThatThrownBy(() -> provider.search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageNotContaining("provider-secret-timeout-detail");
        server.verify();
    }

    @Test
    @DisplayName("Kakao 장소의 malformed coordinate와 비허용 URL을 거부한다")
    void rejectsMalformedCoordinateAndUntrustedPlaceUrl() {
        assertInvalidDocument("""
			{"id":"1","place_name":"카페","x":"not-a-number","y":"37.3","distance":"1"}
			""");
        assertInvalidDocument("""
			{"id":"1","place_name":"카페","x":"127.1","y":"37.3","distance":"1",
			 "place_url":"https://example.com/steal"}
			""");
    }

    @Test
    @DisplayName("Kakao가 반환한 http place URL은 allowlist host를 확인한 뒤 https로 승격한다")
    void upgradesAllowedKakaoPlaceUrlToHttps() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET)).andRespond(withSuccess("""
                        {"meta":{"total_count":1},"documents":[{
                          "id":"1","place_name":"카페","x":"127.1","y":"37.3","distance":"1",
                          "place_url":"http://place.map.kakao.com/1"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        var result = provider(builder).search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE);

        assertThat(result.places())
                .singleElement()
                .extracting(place -> place.placeUrl())
                .isEqualTo("https://place.map.kakao.com/1");
        server.verify();
    }

    @Test
    @DisplayName("Kakao provider 응답은 cache에 최대 15개만 저장할 수 있게 제한한다")
    void limitsProviderResultsToFifteenPlaces() {
        String documents = IntStream.range(0, 16)
                .mapToObj(index -> """
                        {"id":"%d","place_name":"카페 %d","x":"127.1","y":"37.3","distance":"%d"}
                        """.formatted(index, index, index))
                .collect(Collectors.joining(","));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET))
                .andRespond(withSuccess(
                        "{\"meta\":{\"total_count\":16},\"documents\":[" + documents + "]}",
                        MediaType.APPLICATION_JSON));

        var result = provider(builder).search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE);

        assertThat(result.matchedCount()).isEqualTo(16);
        assertThat(result.places()).hasSize(15);
        server.verify();
    }

    private void assertInvalidDocument(String document) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), method(GET))
                .andRespond(withSuccess(
                        "{\"meta\":{\"total_count\":1},\"documents\":[" + document + "]}", MediaType.APPLICATION_JSON));
        KakaoNearbyPlaceProvider provider = provider(builder);

        assertThatThrownBy(() -> provider.search(new NearbyPlacePoint(37.321, 127.109), 800, NearbyPlaceCategory.CAFE))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class);
        server.verify();
    }

    private KakaoNearbyPlaceProvider provider(RestClient.Builder builder) {
        return new KakaoNearbyPlaceProvider(builder.build(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
