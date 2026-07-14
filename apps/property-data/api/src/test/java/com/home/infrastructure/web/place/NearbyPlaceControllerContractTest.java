package com.home.infrastructure.web.place;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.place.NearbyPlaceCategoryResult;
import com.home.application.place.NearbyPlaceCenterUnavailableException;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.application.place.NearbyPlaceUseCase;
import com.home.application.place.NearbyPlacesResult;
import com.home.application.read.ResourceNotFoundException;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NearbyPlaceController.class)
@ActiveProfiles("test")
class NearbyPlaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NearbyPlaceUseCase nearbyPlaceUseCase;

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}/nearby-places는 지도와 chatbot 공용 장소 계약을 반환한다")
    void returnsCanonicalNearbyPlaceResponse() throws Exception {
        Instant retrievedAt = Instant.parse("2026-07-13T03:00:00Z");
        NearbyPlaceItem place = new NearbyPlaceItem(
                "kakao:123456",
                "카페 이름",
                "음식점 > 카페",
                37.322,
                127.108,
                72,
                "경기도 수원시",
                "경기도 수원시 도로명",
                "031-000-0000",
                "https://place.map.kakao.com/123456");
        given(nearbyPlaceUseCase.getNearbyPlaces(eq(501L), eq(800), any(), eq(5)))
                .willReturn(new NearbyPlacesResult(
                        501L,
                        new NearbyPlacePoint(37.321, 127.109),
                        800,
                        retrievedAt.plusSeconds(1),
                        List.of(new NearbyPlaceCategoryResult(
                                NearbyPlaceCategory.CAFE, 18, 1, true, retrievedAt, List.of(place)))));

        mockMvc.perform(get("/api/v1/complex/501/nearby-places")
                        .param("radiusMeters", "800")
                        .param("categories", "CAFE")
                        .param("limitPerCategory", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complexId").value(501))
                .andExpect(jsonPath("$.center.lat").value(37.321))
                .andExpect(jsonPath("$.center.lng").value(127.109))
                .andExpect(jsonPath("$.radiusMeters").value(800))
                .andExpect(jsonPath("$.source.provider").value("KAKAO_LOCAL"))
                .andExpect(jsonPath("$.source.countBasis").value("PROVIDER_SEARCH"))
                .andExpect(jsonPath("$.categories[0].category").value("CAFE"))
                .andExpect(jsonPath("$.categories[0].label").value("카페"))
                .andExpect(jsonPath("$.categories[0].matchedCount").value(18))
                .andExpect(jsonPath("$.categories[0].returnedCount").value(1))
                .andExpect(jsonPath("$.categories[0].hasMore").value(true))
                .andExpect(jsonPath("$.categories[0].places[0].placeId").value("kakao:123456"))
                .andExpect(jsonPath("$.categories[0].places[0].distanceMeters").value(72));
    }

    @Test
    @DisplayName("주변 장소 API는 bounded query parameter를 검증한다")
    void rejectsInvalidBounds() throws Exception {
        mockMvc.perform(get("/api/v1/complex/501/nearby-places")
                        .param("radiusMeters", "99")
                        .param("limitPerCategory", "16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("주변 장소 API는 missing complex와 coordinate/provider 장애를 404·422·503으로 구분한다")
    void mapsDomainFailuresToPublicStatuses() throws Exception {
        given(nearbyPlaceUseCase.getNearbyPlaces(eq(404L), eq(800), any(), eq(5)))
                .willThrow(new ResourceNotFoundException("complex not found"));
        given(nearbyPlaceUseCase.getNearbyPlaces(eq(422L), eq(800), any(), eq(5)))
                .willThrow(new NearbyPlaceCenterUnavailableException("coordinate unavailable"));
        given(nearbyPlaceUseCase.getNearbyPlaces(eq(503L), eq(800), any(), eq(5)))
                .willThrow(new NearbyPlaceProviderUnavailableException("provider unavailable"));

        assertStatus(404L, 404);
        assertStatus(422L, 422);
        assertStatus(503L, 503);
    }

    private void assertStatus(long complexId, int expectedStatus) throws Exception {
        mockMvc.perform(get("/api/v1/complex/{complexId}/nearby-places", complexId)
                        .param("categories", "CAFE"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
