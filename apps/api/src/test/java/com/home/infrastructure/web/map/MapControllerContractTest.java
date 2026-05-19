package com.home.infrastructure.web.map;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.home.application.map.MapUseCase;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

@WebMvcTest(MapController.class)
class MapControllerContractTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MapUseCase mapUseCase;

	@Test
	@DisplayName("POST /api/v1/map/complexes returns canonical V1 complex marker fields")
	void validComplexMarkerRequestReturnsCanonicalMarkerFields() throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkersRequest.class)))
			.willReturn(List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L)));

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
	@DisplayName("POST /api/v1/map/regions returns canonical V1 region marker fields")
	void validRegionMarkerRequestReturnsCanonicalRegionFields() throws Exception {
		given(mapUseCase.getRegionMarkers(any(RegionMarkersRequest.class)))
			.willReturn(List.of(new RegionMarkerResponse(1L, "Seoul", 37.5663, 126.9780, null)));

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
			.andExpect(jsonPath("$[0].parcelId").doesNotExist())
			.andExpect(jsonPath("$[0].latestDealAmount").doesNotExist())
			.andExpect(jsonPath("$[0].unitCntSum").doesNotExist())
			.andExpect(jsonPath("$[0].regionName").doesNotExist())
			.andExpect(jsonPath("$[0].latitude").doesNotExist())
			.andExpect(jsonPath("$[0].longitude").doesNotExist())
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].source").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("POST /api/v1/map/regions returns an empty array when no region markers match")
	void validRegionMarkerRequestCanReturnEmptyArray() throws Exception {
		given(mapUseCase.getRegionMarkers(any(RegionMarkersRequest.class)))
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
	@DisplayName("POST /api/v1/map/regions rejects unsupported region values")
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
	@DisplayName("POST /api/v1/map/regions returns ProblemDetail for invalid bounds")
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
	@DisplayName("POST /api/v1/map/complexes returns ProblemDetail for invalid bounds")
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
	@DisplayName("POST /api/v1/map/regions rejects latitude outside WGS84 range")
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
	@DisplayName("POST /api/v1/map/regions rejects longitude outside WGS84 range")
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
	@DisplayName("POST /api/v1/map/complexes rejects southwest bounds greater than northeast bounds")
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
	@DisplayName("POST /api/v1/map/complexes rejects invalid numeric filter ranges")
	void invalidComplexFilterRangesReturnBadRequest(String ignoredName, String filters) throws Exception {
		expectProblemDetail(mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content(complexRequestWithFilters(filters))));

		verifyNoInteractions(mapUseCase);
	}

	@Test
	@DisplayName("POST /api/v1/map/regions requires the region field")
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
	@DisplayName("POST /api/v1/map/complexes returns ProblemDetail for unexpected server errors")
	void unexpectedComplexMarkerErrorReturnsProblemDetail() throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkersRequest.class)))
			.willThrow(new IllegalStateException("failed to load markers"));

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
			.andExpect(jsonPath("$.timestamp").exists());
	}

	private static Stream<Arguments> invalidComplexFilterRanges() {
		return Stream.of(
			Arguments.of("pyeong min greater than max", """
				"pyeongMin": 40,
				"pyeongMax": 30
				"""),
			Arguments.of("price min greater than max", """
				"priceEokMin": 15.0,
				"priceEokMax": 10.0
				"""),
			Arguments.of("age min greater than max", """
				"ageMin": 30,
				"ageMax": 10
				"""),
			Arguments.of("unit min greater than max", """
				"unitMin": 900,
				"unitMax": 100
				"""),
			Arguments.of("negative pyeong", """
				"pyeongMin": -1
				"""),
			Arguments.of("negative price", """
				"priceEokMin": -1.0
				"""),
			Arguments.of("negative age", """
				"ageMin": -1
				"""),
			Arguments.of("negative unit count", """
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
			.andExpect(jsonPath("$.timestamp").exists());
	}
}
