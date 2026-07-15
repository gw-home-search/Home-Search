package com.home.infrastructure.web.place;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.place.NearbyPlaceCategoryResult;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceUseCase;
import com.home.application.place.NearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restDocs")
@WebMvcTest(NearbyPlaceController.class)
@AutoConfigureRestDocs
@ActiveProfiles("test")
class NearbyPlaceApiRestDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NearbyPlaceUseCase nearbyPlaceUseCase;

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}/nearby-places REST Docs를 생성한다")
    void documentNearbyPlaces() throws Exception {
        Instant retrievedAt = Instant.parse("2026-07-13T03:00:00Z");
        given(nearbyPlaceUseCase.getNearbyPlaces(eq(501L), eq(800), any(), eq(5)))
                .willReturn(new NearbyPlacesResult(
                        501L,
                        new NearbyPlacePoint(37.321, 127.109),
                        800,
                        retrievedAt.plusSeconds(1),
                        List.of(new NearbyPlaceCategoryResult(
                                NearbyPlaceCategory.CAFE,
                                18,
                                1,
                                true,
                                retrievedAt,
                                List.of(new NearbyPlaceItem(
                                        "kakao:123456",
                                        "카페 이름",
                                        "음식점 > 카페",
                                        37.322,
                                        127.108,
                                        72,
                                        "경기도 수원시",
                                        "경기도 수원시 도로명",
                                        "031-000-0000",
                                        "https://place.map.kakao.com/123456"))))));

        var responseDescriptors = responseDescriptors();
        mockMvc.perform(get("/api/v1/complex/{complexId}/nearby-places", 501)
                        .param("radiusMeters", "800")
                        .param("categories", "CAFE")
                        .param("limitPerCategory", "5"))
                .andExpect(status().isOk())
                .andDo(document(
                        "nearby-places-success",
                        pathParameters(parameterWithName("complexId").description("Apartment complex id.")),
                        queryParameters(
                                parameterWithName("radiusMeters")
                                        .optional()
                                        .description("Search radius in meters, 100 to 2000. Default 800."),
                                parameterWithName("categories")
                                        .optional()
                                        .description("Comma-separated or repeated supported product categories."),
                                parameterWithName("limitPerCategory")
                                        .optional()
                                        .description("Returned places per category, 1 to 15. Default 5.")),
                        responseFields(responseDescriptors),
                        resource(builder()
                                .tag("Nearby Places")
                                .summary("Get nearby places for a complex")
                                .description(
                                        "Returns Kakao Local search facts grouped by product category. Counts are provider-search counts, not registered-business totals.")
                                .pathParameters(parameterWithName("complexId").description("Apartment complex id."))
                                .queryParameters(
                                        parameterWithName("radiusMeters")
                                                .optional()
                                                .description("Radius in meters."),
                                        parameterWithName("categories")
                                                .optional()
                                                .description("Product categories."),
                                        parameterWithName("limitPerCategory")
                                                .optional()
                                                .description("Places returned per category."))
                                .responseFields(responseDescriptors)
                                .build())));
    }

    private org.springframework.restdocs.payload.FieldDescriptor[] responseDescriptors() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("complexId").type(JsonFieldType.NUMBER).description("Complex id used for the query."),
            fieldWithPath("center.lat").type(JsonFieldType.NUMBER).description("Canonical complex latitude."),
            fieldWithPath("center.lng").type(JsonFieldType.NUMBER).description("Canonical complex longitude."),
            fieldWithPath("radiusMeters").type(JsonFieldType.NUMBER).description("Search radius in meters."),
            fieldWithPath("source.provider").type(JsonFieldType.STRING).description("Place search provider."),
            fieldWithPath("source.countBasis").type(JsonFieldType.STRING).description("Meaning of matchedCount."),
            fieldWithPath("generatedAt").type(JsonFieldType.STRING).description("Home Search response assembly time."),
            fieldWithPath("categories[].category").type(JsonFieldType.STRING).description("Stable product category."),
            fieldWithPath("categories[].label").type(JsonFieldType.STRING).description("Korean category label."),
            fieldWithPath("categories[].matchedCount")
                    .type(JsonFieldType.NUMBER)
                    .description("Kakao provider-search total count."),
            fieldWithPath("categories[].returnedCount")
                    .type(JsonFieldType.NUMBER)
                    .description("Number of places returned in this response."),
            fieldWithPath("categories[].hasMore")
                    .type(JsonFieldType.BOOLEAN)
                    .description("Whether matchedCount exceeds returnedCount."),
            fieldWithPath("categories[].retrievedAt")
                    .type(JsonFieldType.STRING)
                    .description("Category provider retrieval time."),
            fieldWithPath("categories[].places[].placeId")
                    .type(JsonFieldType.STRING)
                    .description("Provider-qualified place id."),
            fieldWithPath("categories[].places[].name")
                    .type(JsonFieldType.STRING)
                    .description("Place name."),
            fieldWithPath("categories[].places[].categoryDetail")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Provider category hierarchy."),
            fieldWithPath("categories[].places[].lat")
                    .type(JsonFieldType.NUMBER)
                    .description("Place latitude."),
            fieldWithPath("categories[].places[].lng")
                    .type(JsonFieldType.NUMBER)
                    .description("Place longitude."),
            fieldWithPath("categories[].places[].distanceMeters")
                    .type(JsonFieldType.NUMBER)
                    .description("Straight-line provider distance in meters."),
            fieldWithPath("categories[].places[].address")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Lot address."),
            fieldWithPath("categories[].places[].roadAddress")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Road address."),
            fieldWithPath("categories[].places[].phone")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Public place phone number."),
            fieldWithPath("categories[].places[].placeUrl")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Validated Kakao place URL.")
        };
    }
}
