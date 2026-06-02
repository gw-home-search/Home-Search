package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.home.application.coordinate.BuildingFootprintImportCandidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VworldBuildingFootprintSourceTest {

	@Test
	@DisplayName("VWorld WFS feature는 PNU 단건 building footprint 후보로 변환한다")
	void fetchesBuildingFootprintsByPnu() {
		VworldParcelCoordinateProperties properties = properties("DUMMY");
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		VworldBuildingFootprintSource source = new VworldBuildingFootprintSource(builder.build(), properties);
		server.expect(requestTo(startsWith("https://api.example.test/vworld/wfs")))
			.andExpect(queryParam("key", "DUMMY"))
			.andExpect(queryParam("output", "application/json"))
			.andExpect(queryParam("pnu", "4128510200115660000"))
			.andExpect(queryParam("srsName", "EPSG:4326"))
			.andRespond(withSuccess("""
				{
				  "features": [
				    {
				      "id": "dt_d010.1",
				      "bbox": [126.7780, 37.6875, 126.7782, 37.6877],
				      "properties": {
				        "pnu": "4128510200115660000",
				        "buld_nm": "중산마을",
				        "dong_nm": "1001동",
				        "gis_idntfc_no": "G-1001"
				      }
				    },
				    {
				      "id": "dt_d010.2",
				      "bbox": [126.7778, 37.6890, 126.7780, 37.6892],
				      "properties": {
				        "pnu": "4128510200115660000",
				        "buld_nm": "중산마을",
				        "dong_nm": "1006동",
				        "gis_idntfc_no": "G-1006"
				      }
				    },
				    {
				      "id": "dt_d010.other",
				      "bbox": [126.0, 37.0, 126.1, 37.1],
				      "properties": {"pnu": "9999999999999999999", "dong_nm": "9999동"}
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		List<BuildingFootprintImportCandidate> footprints = source.fetchByPnu("4128510200115660000");

		assertThat(footprints)
			.extracting(
				BuildingFootprintImportCandidate::pnu,
				BuildingFootprintImportCandidate::buildingName,
				BuildingFootprintImportCandidate::dongName,
				BuildingFootprintImportCandidate::sourceBuildingKey,
				BuildingFootprintImportCandidate::source,
				BuildingFootprintImportCandidate::snapshotVersion
			)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(
					"4128510200115660000",
					"중산마을",
					"1001동",
					"dt_d010.1",
					"VWORLD_WFS",
					"LIVE"
				),
				org.assertj.core.groups.Tuple.tuple(
					"4128510200115660000",
					"중산마을",
					"1006동",
					"dt_d010.2",
					"VWORLD_WFS",
					"LIVE"
				)
			);
		assertThat(footprints.get(0).latitude()).isEqualByComparingTo("37.6876");
		assertThat(footprints.get(0).longitude()).isEqualByComparingTo("126.7781");
		server.verify();
	}

	@Test
	@DisplayName("VWorld service key가 없으면 HTTP 호출 없이 빈 footprint를 반환한다")
	void blankServiceKeySkipsHttpLookup() {
		VworldParcelCoordinateProperties properties = properties(" ");
		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without VW_SERVICE_KEY");
			})
			.build();
		VworldBuildingFootprintSource source = new VworldBuildingFootprintSource(restClient, properties);

		assertThat(source.fetchByPnu("4128510200115660000")).isEmpty();
	}

	private VworldParcelCoordinateProperties properties(String serviceKey) {
		return new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			serviceKey,
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
	}
}
