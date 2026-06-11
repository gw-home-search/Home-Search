package com.home.infrastructure.web.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.home.application.map.MapUseCase;
import com.home.application.map.ComplexMarkerResult;
import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.RegionMarkerResult;
import com.home.application.map.RegionMarkerQuery;

@WebMvcTest(MapController.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MapControllerContractTest {

	private static final String OFFSET_TIMESTAMP_PATTERN =
		"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})$";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MapUseCase mapUseCase;

	@Test
	@DisplayName("POST /api/v1/map/complexes는 canonical complex marker field를 반환한다")
	void validComplexMarkerRequestReturnsCanonicalMarkerFields() throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkerQuery.class)))
			.willReturn(List.of(new ComplexMarkerResult(
				1001L,
				501L,
				"Sample Apartment",
				37.5123,
				127.0456,
				125000L,
				740L
			)));

		mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].complexId").value(501))
			.andExpect(jsonPath("$[0].name").value("Sample Apartment"))
			.andExpect(jsonPath("$[0].lat").value(37.5123))
			.andExpect(jsonPath("$[0].lng").value(127.0456))
			.andExpect(jsonPath("$[0].latestDealAmount").value(125000))
			.andExpect(jsonPath("$[0].unitCntSum").value(740))
			.andExpect(jsonPath("$[0].id").doesNotExist())
			.andExpect(jsonPath("$[0].latitude").doesNotExist())
			.andExpect(jsonPath("$[0].longitude").doesNotExist())
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].source").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 canonical region marker field를 반환한다")
	void validRegionMarkerRequestReturnsCanonicalRegionFields() throws Exception {
		given(mapUseCase.getRegionMarkers(any(RegionMarkerQuery.class)))
			.willReturn(List.of(new RegionMarkerResult(1L, "Seoul", 37.5663, 126.9780, null, 1200L)));

		mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "region": "si-gun-gu"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].name").value("Seoul"))
			.andExpect(jsonPath("$[0].lat").value(37.5663))
			.andExpect(jsonPath("$[0].lng").value(126.9780))
			.andExpect(jsonPath("$[0].trend").isEmpty())
			.andExpect(jsonPath("$[0].unitCntSum").value(1200))
			.andExpect(jsonPath("$[0].parcelId").doesNotExist())
			.andExpect(jsonPath("$[0].latestDealAmount").doesNotExist())
			.andExpect(jsonPath("$[0].regionName").doesNotExist())
			.andExpect(jsonPath("$[0].latitude").doesNotExist())
			.andExpect(jsonPath("$[0].longitude").doesNotExist())
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].source").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("POST /api/v1/map/complexes는 세대수 metadata가 없으면 unitCntSum null을 보존한다")
	void complexMarkerUnitCountCanBeNullWhenMetadataIsMissing() throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkerQuery.class)))
			.willReturn(List.of(new ComplexMarkerResult(
				1001L,
				501L,
				"Sample Apartment",
				37.5123,
				127.0456,
				125000L,
				null
			)));

		mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].unitCntSum").isEmpty());
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 matching region marker가 없으면 empty array를 반환한다")
	void validRegionMarkerRequestCanReturnEmptyArray() throws Exception {
		given(mapUseCase.getRegionMarkers(any(RegionMarkerQuery.class)))
			.willReturn(List.of());

		mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "region": "si-do"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("[]"));
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 unsupported region value를 거부한다")
	void unsupportedRegionRequestReturnsBadRequest() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "region": "invalid-level"
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 invalid bounds를 ProblemDetail로 반환한다")
	void invalidBoundsReturnsProblemDetailForRegionMarkers() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.70,
					  "swLng": 126.85,
					  "neLat": 37.45,
					  "neLng": 127.20,
					  "region": "si-gun-gu"
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/complexes는 invalid bounds를 ProblemDetail로 반환한다")
	void invalidBoundsReturnsProblemDetailForComplexMarkers() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.70,
					  "swLng": 126.85,
					  "neLat": 37.45,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 WGS84 범위 밖 latitude를 거부한다")
	void invalidLatitudeRangeReturns400() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": -91.0,
					  "swLng": 126.85,
					  "neLat": 37.45,
					  "neLng": 127.20,
					  "region": "si-gun-gu"
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 WGS84 범위 밖 longitude를 거부한다")
	void invalidLongitudeRangeReturns400() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 181.0,
					  "region": "si-gun-gu"
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/complexes는 southwest bounds가 northeast bounds보다 크면 거부한다")
	void southwestGreaterThanNortheastReturns400() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 127.20,
					  "neLat": 37.70,
					  "neLng": 126.85,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidComplexFilterRanges")
	@DisplayName("POST /api/v1/map/complexes는 invalid numeric filter range를 거부한다")
	void invalidComplexFilterRangesReturnBadRequest(String ignoredName, String filters) throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content(complexRequestWithFilters(filters))));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/regions는 region field를 요구한다")
	void missingRegionRequestReturnsBadRequest() throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20
					}
					""")));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/complexes는 unexpected server error를 ProblemDetail로 반환한다")
	void unexpectedComplexMarkerErrorReturnsProblemDetail(CapturedOutput output) throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkerQuery.class)))
			.willThrow(new IllegalStateException("failed to load markers serviceKey=sensitive-marker"));

		mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					"""))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("/docs/index.html#error-code-list"))
			.andExpect(jsonPath("$.title").value("S500"))
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.detail").value("Internal server error."))
			.andExpect(jsonPath("$.exception").value("IllegalStateException"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
		assertThat(output)
			.contains("Unhandled API exception")
			.contains("type=IllegalStateException")
			.contains("MapControllerContractTest")
			.doesNotContain("failed to load markers")
			.doesNotContain("serviceKey")
			.doesNotContain("sensitive-marker");
	}

	private static Stream<Arguments> invalidComplexFilterRanges() {
		return Stream.of(
			Arguments.of("pyeong 최소값이 최대값보다 크다", """
				"pyeongMin": 40,
				"pyeongMax": 30
				"""),
			Arguments.of("price 최소값이 최대값보다 크다", """
				"priceEokMin": 15.0,
				"priceEokMax": 10.0
				"""),
			Arguments.of("age 최소값이 최대값보다 크다", """
				"ageMin": 30,
				"ageMax": 10
				"""),
			Arguments.of("unit 최소값이 최대값보다 크다", """
				"unitMin": 900,
				"unitMax": 100
				"""),
			Arguments.of("pyeong이 음수다", """
				"pyeongMin": -1
				"""),
			Arguments.of("price가 음수다", """
				"priceEokMin": -1.0
				"""),
			Arguments.of("age가 음수다", """
				"ageMin": -1
				"""),
			Arguments.of("unit count가 음수다", """
				"unitMin": -1
				""")
		);
	}

	private String complexRequestWithFilters(String filters) {
		return """
			{
			  "swLat": 37.45,
			  "swLng": 126.85,
			  "neLat": 37.70,
			  "neLng": 127.20,
			  %s
			}
			""".formatted(filters);
	}

	private void expectProblemDetail(ResultActions actions) throws Exception {
		actions
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("/docs/index.html#error-code-list"))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("Invalid parameter format."))
			.andExpect(jsonPath("$.exception").value("MapApiException"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}
}
