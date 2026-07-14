package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PublicBuildingMetadataSourceClientTest {
    private static final String PNU = "1168010300101400001";

    @Test
    @DisplayName("source client는 ODC와 건축물대장 endpoint에 PNU exact·100건 제한을 적용한다")
    void fetchesOdcRecapAndTitleWithBoundedQueries() {
        RestClient.Builder odcBuilder = RestClient.builder().baseUrl("https://odc.example.test");
        RestClient.Builder buildingBuilder = RestClient.builder().baseUrl("https://bld.example.test");
        MockRestServiceServer odcServer =
                MockRestServiceServer.bindTo(odcBuilder).build();
        MockRestServiceServer buildingServer =
                MockRestServiceServer.bindTo(buildingBuilder).build();
        PublicBuildingMetadataSourceClient client = new PublicBuildingMetadataSourceClient(
                odcBuilder.build(),
                "https://odc.example.test",
                "ODC-KEY",
                "/apt",
                buildingBuilder.build(),
                "https://bld.example.test",
                "BLD-KEY",
                "/recap",
                "/title",
                0);
        odcServer
                .expect(requestTo(startsWith("https://odc.example.test/apt")))
                .andExpect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/apt");
                    assertThat(request.getURI().getRawQuery())
                            .contains("perPage=100", "cond%5BPNU::EQ%5D=" + PNU, "serviceKey=ODC-KEY");
                })
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        buildingServer
                .expect(requestTo(startsWith("https://bld.example.test/recap")))
                .andExpect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/recap");
                    assertThat(request.getURI().getRawQuery())
                            .contains(
                                    "numOfRows=100",
                                    "sigunguCd=11680",
                                    "bjdongCd=10300",
                                    "platGbCd=0",
                                    "bun=0140",
                                    "ji=0001");
                })
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));
        buildingServer
                .expect(requestTo(startsWith("https://bld.example.test/title")))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(BuildingMetadataSourceKind.ODC_APT, PNU).body()).contains("data");
        assertThat(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU).httpStatus())
                .isEqualTo(200);
        assertThat(client.fetch(BuildingMetadataSourceKind.BLD_TITLE, PNU).sourceKind())
                .isEqualTo(BuildingMetadataSourceKind.BLD_TITLE);
        odcServer.verify();
        buildingServer.verify();
    }

    @Test
    @DisplayName("source client는 key 미설정과 잘못된 PNU를 요청 전에 거부한다")
    void rejectsMissingKeysAndInvalidPnu() {
        PublicBuildingMetadataSourceClient client = new PublicBuildingMetadataSourceClient(
                RestClient.create(),
                "https://odc",
                " ",
                "/apt",
                RestClient.create(),
                "https://bld",
                " ",
                "/recap",
                "/title",
                0);

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.fetch(BuildingMetadataSourceKind.ODC_APT, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fetch(BuildingMetadataSourceKind.ODC_APT, PNU))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ODC_SERVICE_KEY");
    }

    @Test
    @DisplayName("source client는 PNU 산 필지를 건축물대장 platGbCd 1로 변환하고 지원하지 않는 구분을 거부한다")
    void convertsMountainLandCategoryAndRejectsUnsupportedCategory() {
        RestClient.Builder buildingBuilder = RestClient.builder().baseUrl("https://bld.example.test");
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(buildingBuilder).build();
        PublicBuildingMetadataSourceClient client = new PublicBuildingMetadataSourceClient(
                RestClient.create(),
                "https://odc.example.test",
                "ODC-KEY",
                "/apt",
                buildingBuilder.build(),
                "https://bld.example.test",
                "BLD-KEY",
                "/recap",
                "/title",
                0);
        server.expect(requestTo(startsWith("https://bld.example.test/title")))
                .andExpect(request ->
                        assertThat(request.getURI().getRawQuery()).contains("platGbCd=1", "bun=0140", "ji=0001"))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        client.fetch(BuildingMetadataSourceKind.BLD_TITLE, "1168010300201400001");

        server.verify();
        assertThatThrownBy(() -> client.fetch(BuildingMetadataSourceKind.BLD_TITLE, "1168010300001400001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("land category");
    }

    @Test
    @DisplayName("source client는 2 MiB 초과 응답을 메모리 body로 반환하지 않고 정확한 hash와 크기만 전달한다")
    void streamsOversizedResponseWithoutRetainingBody() {
        RestClient.Builder odcBuilder = RestClient.builder().baseUrl("https://odc.example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(odcBuilder).build();
        String body = "x".repeat(2_097_152 + 17);
        server.expect(requestTo(startsWith("https://odc.example.test/apt")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        PublicBuildingMetadataSourceClient client = new PublicBuildingMetadataSourceClient(
                odcBuilder.build(),
                "https://odc.example.test",
                "ODC-KEY",
                "/apt",
                RestClient.create(),
                "https://bld.example.test",
                "BLD-KEY",
                "/recap",
                "/title",
                0);

        var response = client.fetch(BuildingMetadataSourceKind.ODC_APT, PNU);

        assertThat(response.body()).isNull();
        assertThat(response.payloadOversized()).isTrue();
        assertThat(response.observedBodyByteSize()).isEqualTo(body.length());
        assertThat(response.observedResponseHash()).hasSize(64);
    }
}
