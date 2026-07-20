package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.home.application.ingest.buildingregister.BuildingRegisterPageRequest;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PublicBuildingRegisterPageClientTest {
    private static final String PNU = "1168010300101400001";

    @Test
    @DisplayName("건축물대장 외부 페이지 요청을 검증한다")
    void callsAllBuildingRegisterEndpointsWithExplicitPagination() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bld.example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PublicBuildingRegisterPageClient client = new PublicBuildingRegisterPageClient(
                builder.build(), "https://bld.example.test", "BLD-KEY", "/recap", "/title", "/basic", 0);
        for (String path : new String[] {"/recap", "/title", "/basic"}) {
            server.expect(requestTo(startsWith("https://bld.example.test" + path)))
                    .andExpect(request -> assertThat(request.getURI().getRawQuery())
                            .contains(
                                    "pageNo=2",
                                    "numOfRows=25",
                                    "sigunguCd=11680",
                                    "bjdongCd=10300",
                                    "platGbCd=0",
                                    "bun=0140",
                                    "ji=0001",
                                    "serviceKey=BLD-KEY"))
                    .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));
        }

        for (BuildingRegisterEndpoint endpoint : BuildingRegisterEndpoint.values()) {
            var response = client.fetch(new BuildingRegisterPageRequest(endpoint, PNU, 2, 25));
            assertThat(response.endpoint()).isEqualTo(endpoint);
            assertThat(response.pageNo()).isEqualTo(2);
            assertThat(response.pageSize()).isEqualTo(25);
            assertThat(response.bodySha256()).hasSize(64);
        }
        server.verify();
    }

    @Test
    @DisplayName("건축물대장 외부 페이지 요청을 검증한다")
    void dropsOversizedBodyButKeepsHashAndObservedSize() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bld.example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String body = "x".repeat(2_097_153);
        server.expect(requestTo(startsWith("https://bld.example.test/title")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        PublicBuildingRegisterPageClient client = new PublicBuildingRegisterPageClient(
                builder.build(), "https://bld.example.test", "BLD-KEY", "/recap", "/title", "/basic", 0);

        var response = client.fetch(new BuildingRegisterPageRequest(BuildingRegisterEndpoint.TITLE, PNU, 1, 100));

        assertThat(response.oversized()).isTrue();
        assertThat(response.body()).isNull();
        assertThat(response.byteCount()).isEqualTo(body.length());
        assertThat(response.bodySha256()).hasSize(64);
    }
}
