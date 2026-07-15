package com.home.infrastructure.web.place;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.ViewportNearbyPlaceCategoryResult;
import com.home.application.place.ViewportNearbyPlaceUseCase;
import com.home.application.place.ViewportNearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restDocs")
@WebMvcTest(ViewportNearbyPlaceController.class)
@AutoConfigureRestDocs
@ActiveProfiles("test")
class ViewportNearbyPlaceApiRestDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ViewportNearbyPlaceUseCase useCase;

    @Test
    @DisplayName("POST /api/v1/map/nearby-places REST Docs를 생성한다")
    void documentViewportNearbyPlaces() throws Exception {
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.50, 126.93);
        Instant retrievedAt = Instant.parse("2026-07-15T03:59:50Z");
        given(useCase.getNearbyPlaces(eq(bounds), eq(4), eq(NearbyPlaceCategory.CAFE)))
                .willReturn(new ViewportNearbyPlacesResult(
                        bounds,
                        4,
                        retrievedAt.plusSeconds(10),
                        new ViewportNearbyPlaceCategoryResult(
                                NearbyPlaceCategory.CAFE,
                                retrievedAt,
                                List.of(new NearbyPlaceItem(
                                        "kakao:123456",
                                        "카페 이름",
                                        "음식점 > 카페",
                                        37.49,
                                        126.91,
                                        170,
                                        "서울특별시",
                                        "서울특별시 도로명",
                                        "02-000-0000",
                                        "https://place.map.kakao.com/123456")))));

        FieldDescriptor[] requestDescriptors = requestDescriptors();
        FieldDescriptor[] responseDescriptors = responseDescriptors();
        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "swLat": 37.45,
                                  "swLng": 126.85,
                                  "neLat": 37.50,
                                  "neLng": 126.93,
                                  "level": 4,
                                  "category": "CAFE"
                                }
                                """))
                .andExpect(status().isOk())
                .andDo(document(
                        "viewport-nearby-places-success",
                        requestFields(requestDescriptors),
                        responseFields(responseDescriptors),
                        resource(builder()
                                .tag("Nearby Places")
                                .summary("Get nearby places for the current map viewport")
                                .description(
                                        "Returns at most ten Kakao Local places for one selected category inside the requested viewport.")
                                .requestFields(requestDescriptors)
                                .responseFields(responseDescriptors)
                                .build())));
    }

    private FieldDescriptor[] requestDescriptors() {
        return new FieldDescriptor[] {
            fieldWithPath("swLat").type(JsonFieldType.NUMBER).description("South-west latitude, WGS84."),
            fieldWithPath("swLng").type(JsonFieldType.NUMBER).description("South-west longitude, WGS84."),
            fieldWithPath("neLat").type(JsonFieldType.NUMBER).description("North-east latitude, WGS84."),
            fieldWithPath("neLng").type(JsonFieldType.NUMBER).description("North-east longitude, WGS84."),
            fieldWithPath("level").type(JsonFieldType.NUMBER).description("Kakao map level from 1 to 4."),
            fieldWithPath("category").type(JsonFieldType.STRING).description("One supported product category.")
        };
    }

    private FieldDescriptor[] responseDescriptors() {
        return new FieldDescriptor[] {
            fieldWithPath("bounds.swLat").type(JsonFieldType.NUMBER).description("Requested south-west latitude."),
            fieldWithPath("bounds.swLng").type(JsonFieldType.NUMBER).description("Requested south-west longitude."),
            fieldWithPath("bounds.neLat").type(JsonFieldType.NUMBER).description("Requested north-east latitude."),
            fieldWithPath("bounds.neLng").type(JsonFieldType.NUMBER).description("Requested north-east longitude."),
            fieldWithPath("level").type(JsonFieldType.NUMBER).description("Requested Kakao map level."),
            fieldWithPath("source.provider").type(JsonFieldType.STRING).description("Place search provider."),
            fieldWithPath("source.countBasis").type(JsonFieldType.STRING).description("Provider search basis."),
            fieldWithPath("generatedAt").type(JsonFieldType.STRING).description("Home Search response assembly time."),
            fieldWithPath("category.category").type(JsonFieldType.STRING).description("Stable product category."),
            fieldWithPath("category.label").type(JsonFieldType.STRING).description("Korean category label."),
            fieldWithPath("category.retrievedAt").type(JsonFieldType.STRING).description("Provider retrieval time."),
            fieldWithPath("category.places[].placeId")
                    .type(JsonFieldType.STRING)
                    .description("Provider-qualified place id."),
            fieldWithPath("category.places[].name").type(JsonFieldType.STRING).description("Place name."),
            fieldWithPath("category.places[].categoryDetail")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Provider category hierarchy."),
            fieldWithPath("category.places[].lat").type(JsonFieldType.NUMBER).description("Place latitude."),
            fieldWithPath("category.places[].lng").type(JsonFieldType.NUMBER).description("Place longitude."),
            fieldWithPath("category.places[].distanceMeters")
                    .type(JsonFieldType.NUMBER)
                    .description("Distance from map center in meters."),
            fieldWithPath("category.places[].address")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Lot address."),
            fieldWithPath("category.places[].roadAddress")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Road address."),
            fieldWithPath("category.places[].phone")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Public phone number."),
            fieldWithPath("category.places[].placeUrl")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Validated Kakao place URL.")
        };
    }
}
