package com.home.infrastructure.web.place;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.place.InvalidNearbyPlaceRequestException;
import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.application.place.ViewportNearbyPlaceCategoryResult;
import com.home.application.place.ViewportNearbyPlaceUseCase;
import com.home.application.place.ViewportNearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ViewportNearbyPlaceController.class)
@ActiveProfiles("test")
class ViewportNearbyPlaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ViewportNearbyPlaceUseCase useCase;

    @Test
    @DisplayName("POST /api/v1/map/nearby-places는 단일 category viewport 장소 계약을 반환한다")
    void returnsViewportNearbyPlaces() throws Exception {
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.52, 126.95);
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

        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "swLat": 37.45,
                                  "swLng": 126.85,
                                  "neLat": 37.52,
                                  "neLng": 126.95,
                                  "level": 4,
                                  "category": "CAFE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bounds.swLat").value(37.45))
                .andExpect(jsonPath("$.level").value(4))
                .andExpect(jsonPath("$.source.provider").value("KAKAO_LOCAL"))
                .andExpect(jsonPath("$.category.category").value("CAFE"))
                .andExpect(jsonPath("$.category.label").value("카페"))
                .andExpect(jsonPath("$.category.places[0].placeId").value("kakao:123456"));
    }

    @Test
    @DisplayName("viewport API는 level과 bounds를 400으로 검증한다")
    void rejectsInvalidViewport() throws Exception {
        given(useCase.getNearbyPlaces(
                        eq(new NearbyPlaceBounds(37.52, 126.95, 37.45, 126.85)), eq(5), eq(NearbyPlaceCategory.CAFE)))
                .willThrow(new InvalidNearbyPlaceRequestException("bounds are invalid"));

        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "swLat": 37.52,
                                  "swLng": 126.95,
                                  "neLat": 37.45,
                                  "neLng": 126.85,
                                  "level": 5,
                                  "category": "CAFE"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("viewport API는 정상적인 빈 결과를 200으로 반환한다")
    void returnsEmptyViewport() throws Exception {
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.50, 126.93);
        Instant retrievedAt = Instant.parse("2026-07-15T03:59:50Z");
        given(useCase.getNearbyPlaces(eq(bounds), eq(4), eq(NearbyPlaceCategory.CAFE)))
                .willReturn(new ViewportNearbyPlacesResult(
                        bounds,
                        4,
                        retrievedAt.plusSeconds(10),
                        new ViewportNearbyPlaceCategoryResult(NearbyPlaceCategory.CAFE, retrievedAt, List.of())));

        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.places").isEmpty());
    }

    @Test
    @DisplayName("viewport API는 신규 대형마트 category를 허용한다")
    void acceptsSupermarketCategory() throws Exception {
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.50, 126.93);
        Instant retrievedAt = Instant.parse("2026-07-15T03:59:50Z");
        given(useCase.getNearbyPlaces(eq(bounds), eq(4), eq(NearbyPlaceCategory.SUPERMARKET)))
                .willReturn(new ViewportNearbyPlacesResult(
                        bounds,
                        4,
                        retrievedAt,
                        new ViewportNearbyPlaceCategoryResult(
                                NearbyPlaceCategory.SUPERMARKET, retrievedAt, List.of())));

        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "swLat": 37.45,
                                  "swLng": 126.85,
                                  "neLat": 37.50,
                                  "neLng": 126.93,
                                  "level": 4,
                                  "category": "SUPERMARKET"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.category").value("SUPERMARKET"))
                .andExpect(jsonPath("$.category.label").value("대형마트"));
    }

    @Test
    @DisplayName("viewport provider 장애는 외부 내용을 노출하지 않고 503으로 반환한다")
    void returnsServiceUnavailable() throws Exception {
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.50, 126.93);
        given(useCase.getNearbyPlaces(eq(bounds), eq(4), eq(NearbyPlaceCategory.CAFE)))
                .willThrow(new NearbyPlaceProviderUnavailableException("Authorization: KakaoAK secret"));

        mockMvc.perform(post("/api/v1/map/nearby-places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Nearby place provider unavailable."))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    private String validRequest() {
        return """
                {
                  "swLat": 37.45,
                  "swLng": 126.85,
                  "neLat": 37.50,
                  "neLng": 126.93,
                  "level": 4,
                  "category": "CAFE"
                }
                """;
    }
}
