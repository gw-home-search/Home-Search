package com.home.infrastructure.web.map;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.home.application.map.MapUseCase;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

@Tag("restDocs")
@WebMvcTest(MapController.class)
@AutoConfigureRestDocs
@ActiveProfiles("test")
class MapApiRestDocsTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MapUseCase mapUseCase;

	@Test
	@DisplayName("POST /api/v1/map/regions REST Docs를 생성한다")
	void documentRegionMarkers() throws Exception {
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
			.andDo(document("map-regions-success",
				requestFields(
					fieldWithPath("swLat").type(JsonFieldType.NUMBER).description("Southwest latitude."),
					fieldWithPath("swLng").type(JsonFieldType.NUMBER).description("Southwest longitude."),
					fieldWithPath("neLat").type(JsonFieldType.NUMBER).description("Northeast latitude."),
					fieldWithPath("neLng").type(JsonFieldType.NUMBER).description("Northeast longitude."),
					fieldWithPath("region").type(JsonFieldType.STRING).description("Region level: si-do, si-gun-gu, or eup-myeon-dong.")
				),
				responseFields(
					fieldWithPath("[].id").type(JsonFieldType.NUMBER).description("Region id."),
					fieldWithPath("[].name").type(JsonFieldType.STRING).description("Region display name."),
					fieldWithPath("[].lat").type(JsonFieldType.NUMBER).description("Marker latitude."),
					fieldWithPath("[].lng").type(JsonFieldType.NUMBER).description("Marker longitude."),
					fieldWithPath("[].trend").type(JsonFieldType.NULL).optional().description("Optional regional trend value.")
				),
				resource(builder()
					.tag("Map")
					.summary("Get region markers")
					.description("Returns region-level markers inside map bounds.")
					.requestFields(
						fieldWithPath("swLat").type(JsonFieldType.NUMBER).description("Southwest latitude."),
						fieldWithPath("swLng").type(JsonFieldType.NUMBER).description("Southwest longitude."),
						fieldWithPath("neLat").type(JsonFieldType.NUMBER).description("Northeast latitude."),
						fieldWithPath("neLng").type(JsonFieldType.NUMBER).description("Northeast longitude."),
						fieldWithPath("region").type(JsonFieldType.STRING).description("Region level.")
					)
					.responseFields(
						fieldWithPath("[].id").type(JsonFieldType.NUMBER).description("Region id."),
						fieldWithPath("[].name").type(JsonFieldType.STRING).description("Region display name."),
						fieldWithPath("[].lat").type(JsonFieldType.NUMBER).description("Marker latitude."),
						fieldWithPath("[].lng").type(JsonFieldType.NUMBER).description("Marker longitude."),
						fieldWithPath("[].trend").type(JsonFieldType.NULL).optional().description("Optional regional trend value.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("POST /api/v1/map/complexes REST Docs를 생성한다")
	void documentComplexMarkers() throws Exception {
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
			.andDo(document("map-complexes-success",
				requestFields(
					fieldWithPath("swLat").type(JsonFieldType.NUMBER).description("Southwest latitude."),
					fieldWithPath("swLng").type(JsonFieldType.NUMBER).description("Southwest longitude."),
					fieldWithPath("neLat").type(JsonFieldType.NUMBER).description("Northeast latitude."),
					fieldWithPath("neLng").type(JsonFieldType.NUMBER).description("Northeast longitude."),
					fieldWithPath("pyeongMin").type(JsonFieldType.NULL).optional().description("Minimum area in pyeong."),
					fieldWithPath("pyeongMax").type(JsonFieldType.NULL).optional().description("Maximum area in pyeong."),
					fieldWithPath("priceEokMin").type(JsonFieldType.NULL).optional().description("Minimum price in eok units."),
					fieldWithPath("priceEokMax").type(JsonFieldType.NULL).optional().description("Maximum price in eok units."),
					fieldWithPath("ageMin").type(JsonFieldType.NULL).optional().description("Minimum building age."),
					fieldWithPath("ageMax").type(JsonFieldType.NULL).optional().description("Maximum building age."),
					fieldWithPath("unitMin").type(JsonFieldType.NULL).optional().description("Minimum household count."),
					fieldWithPath("unitMax").type(JsonFieldType.NULL).optional().description("Maximum household count.")
				),
				responseFields(
					fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id used by detail and trade APIs."),
					fieldWithPath("[].lat").type(JsonFieldType.NUMBER).description("Marker latitude."),
					fieldWithPath("[].lng").type(JsonFieldType.NUMBER).description("Marker longitude."),
					fieldWithPath("[].latestDealAmount").type(JsonFieldType.NUMBER).description("Latest trade amount in 10,000 KRW units."),
					fieldWithPath("[].unitCntSum").type(JsonFieldType.NUMBER).description("Total household count under the parcel.")
				),
				resource(builder()
					.tag("Map")
					.summary("Get complex markers")
					.description("Returns parcel-level apartment complex markers inside map bounds.")
					.requestFields(
						fieldWithPath("swLat").type(JsonFieldType.NUMBER).description("Southwest latitude."),
						fieldWithPath("swLng").type(JsonFieldType.NUMBER).description("Southwest longitude."),
						fieldWithPath("neLat").type(JsonFieldType.NUMBER).description("Northeast latitude."),
						fieldWithPath("neLng").type(JsonFieldType.NUMBER).description("Northeast longitude."),
						fieldWithPath("pyeongMin").type(JsonFieldType.NULL).optional().description("Minimum area in pyeong."),
						fieldWithPath("pyeongMax").type(JsonFieldType.NULL).optional().description("Maximum area in pyeong."),
						fieldWithPath("priceEokMin").type(JsonFieldType.NULL).optional().description("Minimum price in eok units."),
						fieldWithPath("priceEokMax").type(JsonFieldType.NULL).optional().description("Maximum price in eok units."),
						fieldWithPath("ageMin").type(JsonFieldType.NULL).optional().description("Minimum building age."),
						fieldWithPath("ageMax").type(JsonFieldType.NULL).optional().description("Maximum building age."),
						fieldWithPath("unitMin").type(JsonFieldType.NULL).optional().description("Minimum household count."),
						fieldWithPath("unitMax").type(JsonFieldType.NULL).optional().description("Maximum household count.")
					)
					.responseFields(
						fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("[].lat").type(JsonFieldType.NUMBER).description("Marker latitude."),
						fieldWithPath("[].lng").type(JsonFieldType.NUMBER).description("Marker longitude."),
						fieldWithPath("[].latestDealAmount").type(JsonFieldType.NUMBER).description("Latest trade amount."),
						fieldWithPath("[].unitCntSum").type(JsonFieldType.NUMBER).description("Total household count.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("map ProblemDetail error REST Docs를 생성한다")
	void documentProblemDetail() throws Exception {
		mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.70,
					  "swLng": 126.85,
					  "neLat": 37.45,
					  "neLng": 127.20,
					  "region": "si-gun-gu"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andDo(document("map-invalid-request",
				responseFields(
					fieldWithPath("type").type(JsonFieldType.STRING).description("Documentation anchor for the error list."),
					fieldWithPath("title").type(JsonFieldType.STRING).description("public API error code."),
						fieldWithPath("status").type(JsonFieldType.NUMBER).description("HTTP status code."),
						fieldWithPath("detail").type(JsonFieldType.STRING).description("Human-readable error detail."),
						fieldWithPath("exception").type(JsonFieldType.STRING).description("Exception or public API error category."),
						fieldWithPath("timestamp").type(JsonFieldType.STRING).description("UTC offset error timestamp."),
						fieldWithPath("instance").type(JsonFieldType.STRING).optional().description("Request path that produced the error.")
					),
					resource(builder()
					.tag("Map")
					.summary("Map request validation error")
					.description("Returns ProblemDetail when a map request is invalid.")
					.responseFields(
						fieldWithPath("type").type(JsonFieldType.STRING).description("Documentation anchor for the error list."),
						fieldWithPath("title").type(JsonFieldType.STRING).description("public API error code."),
							fieldWithPath("status").type(JsonFieldType.NUMBER).description("HTTP status code."),
							fieldWithPath("detail").type(JsonFieldType.STRING).description("Human-readable error detail."),
							fieldWithPath("exception").type(JsonFieldType.STRING).description("Exception or public API error category."),
							fieldWithPath("timestamp").type(JsonFieldType.STRING).description("UTC offset error timestamp."),
							fieldWithPath("instance").type(JsonFieldType.STRING).optional().description("Request path that produced the error.")
						)
						.build())
			));
	}
}
