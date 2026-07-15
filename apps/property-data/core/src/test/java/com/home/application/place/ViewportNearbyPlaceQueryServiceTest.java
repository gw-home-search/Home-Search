package com.home.application.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.home.domain.place.NearbyPlaceCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViewportNearbyPlaceQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T04:00:00Z");

    @Test
    @DisplayName("viewport 조회는 bounds를 바깥 격자로 정규화하고 실제 화면 안 장소만 중심 거리순으로 반환한다")
    void normalizesBoundsAndFiltersActualViewport() {
        AtomicReference<NearbyPlaceProviderQuery> captured = new AtomicReference<>();
        NearbyPlaceProvider provider = query -> {
            captured.set(query);
            return new NearbyPlaceProviderResult(
                    NearbyPlaceCategory.CAFE,
                    3,
                    NOW.minusSeconds(10),
                    List.of(
                            place("kakao:outside", "화면 밖", 37.5105, 126.91),
                            place("kakao:far", "화면 안 먼 곳", 37.50, 126.92),
                            place("kakao:near", "화면 안 가까운 곳", 37.485, 126.90)));
        };
        ViewportNearbyPlaceQueryService service = service(provider);

        ViewportNearbyPlacesResult result = service.getNearbyPlaces(
                new NearbyPlaceBounds(37.4504, 126.8504, 37.5104, 126.9304), 4, NearbyPlaceCategory.CAFE);

        assertThat(captured.get().category()).isEqualTo(NearbyPlaceCategory.CAFE);
        assertThat(captured.get().area()).isInstanceOfSatisfying(NearbyPlaceBoundsArea.class, area -> {
            assertThat(area.bounds()).isEqualTo(new NearbyPlaceBounds(37.450, 126.850, 37.511, 126.931));
            assertThat(area.center().lat()).isCloseTo(37.4805, within(0.000_000_1));
            assertThat(area.center().lng()).isCloseTo(126.8905, within(0.000_000_1));
        });
        assertThat(result.bounds()).isEqualTo(new NearbyPlaceBounds(37.4504, 126.8504, 37.5104, 126.9304));
        assertThat(result.level()).isEqualTo(4);
        assertThat(result.category().places())
                .extracting(NearbyPlaceItem::name)
                .containsExactly("화면 안 가까운 곳", "화면 안 먼 곳");
        assertThat(result.category().places())
                .extracting(NearbyPlaceItem::distanceMeters)
                .isSorted();
    }

    @Test
    @DisplayName("viewport 조회는 level과 bounds 크기를 제한한다")
    void validatesLevelAndBounds() {
        ViewportNearbyPlaceQueryService service =
                service(query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of()));

        assertThatThrownBy(() -> service.getNearbyPlaces(
                        new NearbyPlaceBounds(37.45, 126.85, 37.52, 126.95), 5, NearbyPlaceCategory.CAFE))
                .isInstanceOf(InvalidNearbyPlaceRequestException.class);
        assertThatThrownBy(() -> service.getNearbyPlaces(
                        new NearbyPlaceBounds(37.0, 126.0, 38.0, 127.0), 4, NearbyPlaceCategory.CAFE))
                .isInstanceOf(InvalidNearbyPlaceRequestException.class);
    }

    @Test
    @DisplayName("viewport provider timeout과 executor 포화는 503용 실패로 변환한다")
    void boundsProviderExecution() {
        NearbyPlaceProvider provider = query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of());
        NearbyPlaceBounds bounds = new NearbyPlaceBounds(37.45, 126.85, 37.50, 126.93);
        ViewportNearbyPlaceQueryService timeoutService = new ViewportNearbyPlaceQueryService(
                provider,
                new NearbyPlaceExecutionOptions(command -> {}, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(1)));
        ViewportNearbyPlaceQueryService saturatedService = new ViewportNearbyPlaceQueryService(
                provider,
                new NearbyPlaceExecutionOptions(
                        command -> {
                            throw new RejectedExecutionException("saturated");
                        },
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofSeconds(5)));

        assertThatThrownBy(() -> timeoutService.getNearbyPlaces(bounds, 4, NearbyPlaceCategory.CAFE))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageContaining("timed out");
        assertThatThrownBy(() -> saturatedService.getNearbyPlaces(bounds, 4, NearbyPlaceCategory.CAFE))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageNotContaining("saturated");
    }

    private NearbyPlaceItem place(String id, String name, double lat, double lng) {
        return new NearbyPlaceItem(
                id, name, "음식점 > 카페", lat, lng, 0, "서울특별시", "서울특별시 도로명", null, "https://place.map.kakao.com/123456");
    }

    private ViewportNearbyPlaceQueryService service(NearbyPlaceProvider provider) {
        return new ViewportNearbyPlaceQueryService(
                provider,
                new NearbyPlaceExecutionOptions(
                        Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5)));
    }
}
