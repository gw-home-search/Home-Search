package com.home.application.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.read.ResourceNotFoundException;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NearbyPlaceQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T03:00:00Z");

    @Test
    @DisplayName("주변 장소 조회는 provider 결과를 거리순으로 제한하고 count와 조회 시점을 보존한다")
    void aggregatesProviderResultsByCategory() {
        List<NearbyPlaceCategory> calledCategories = new ArrayList<>();
        NearbyPlaceProvider provider = query -> {
            NearbyPlaceCategory category = query.category();
            calledCategories.add(category);
            return new NearbyPlaceProviderResult(
                    category, 18, NOW, List.of(place("kakao:2", "먼 카페", 230), place("kakao:1", "가까운 카페", 72)));
        };
        NearbyPlaceQueryService service =
                service(complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)), provider);

        NearbyPlacesResult result = service.getNearbyPlaces(501L, 800, List.of(NearbyPlaceCategory.CAFE), 1);

        assertThat(calledCategories).containsExactly(NearbyPlaceCategory.CAFE);
        assertThat(result.complexId()).isEqualTo(501L);
        assertThat(result.center()).isEqualTo(new NearbyPlacePoint(37.321, 127.109));
        assertThat(result.radiusMeters()).isEqualTo(800);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.categories()).singleElement().satisfies(category -> {
            assertThat(category.category()).isEqualTo(NearbyPlaceCategory.CAFE);
            assertThat(category.matchedCount()).isEqualTo(18);
            assertThat(category.returnedCount()).isEqualTo(1);
            assertThat(category.hasMore()).isTrue();
            assertThat(category.retrievedAt()).isEqualTo(NOW);
            assertThat(category.places()).extracting(NearbyPlaceItem::name).containsExactly("가까운 카페");
        });
    }

    @Test
    @DisplayName("category를 생략하면 제품 순서로 8개 기본 category를 조회한다")
    void defaultsToAllSupportedCategories() {
        List<NearbyPlaceCategory> calledCategories = new ArrayList<>();
        NearbyPlaceQueryService service =
                service(complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)), query -> {
                    NearbyPlaceCategory category = query.category();
                    calledCategories.add(category);
                    return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
                });

        NearbyPlacesResult result = service.getNearbyPlaces(501L, null, null, null);

        assertThat(calledCategories)
                .containsExactly(
                        NearbyPlaceCategory.SUPERMARKET,
                        NearbyPlaceCategory.CONVENIENCE_STORE,
                        NearbyPlaceCategory.RESTAURANT,
                        NearbyPlaceCategory.DAYCARE_KINDERGARTEN,
                        NearbyPlaceCategory.SCHOOL,
                        NearbyPlaceCategory.ACADEMY,
                        NearbyPlaceCategory.SUBWAY_STATION,
                        NearbyPlaceCategory.HOSPITAL);
        assertThat(result.radiusMeters()).isEqualTo(800);
        assertThat(result.categories()).hasSize(8);
    }

    @Test
    @DisplayName("존재하지 않는 단지와 좌표 없는 단지를 구분한다")
    void distinguishesMissingComplexAndMissingCoordinate() {
        NearbyPlaceProvider provider = query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of());

        assertThatThrownBy(
                        () -> service(complexId -> Optional.empty(), provider).getNearbyPlaces(999L, 800, null, 5))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service(
                                complexId -> Optional.of(new NearbyPlaceCenter(complexId, null, null)), provider)
                        .getNearbyPlaces(501L, 800, null, 5))
                .isInstanceOf(NearbyPlaceCenterUnavailableException.class);
    }

    @Test
    @DisplayName("반경과 category당 결과 개수는 bounded range만 허용한다")
    void validatesBoundedInputs() {
        NearbyPlaceQueryService service = service(
                complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)),
                query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of()));

        assertThatThrownBy(() -> service.getNearbyPlaces(501L, 99, null, 5))
                .isInstanceOf(InvalidNearbyPlaceRequestException.class);
        assertThatThrownBy(() -> service.getNearbyPlaces(501L, 800, null, 16))
                .isInstanceOf(InvalidNearbyPlaceRequestException.class);
    }

    @Test
    @DisplayName("중복 category는 제거하고 제품 고정 순서로 한 번씩 조회한다")
    void deduplicatesCategoriesInProductOrder() {
        List<NearbyPlaceCategory> calledCategories = new ArrayList<>();
        NearbyPlaceQueryService service =
                service(complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)), query -> {
                    NearbyPlaceCategory category = query.category();
                    calledCategories.add(category);
                    return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
                });

        service.getNearbyPlaces(
                501L,
                800,
                List.of(NearbyPlaceCategory.RESTAURANT, NearbyPlaceCategory.CAFE, NearbyPlaceCategory.RESTAURANT),
                5);

        assertThat(calledCategories).containsExactly(NearbyPlaceCategory.CAFE, NearbyPlaceCategory.RESTAURANT);
    }

    @Test
    @DisplayName("기본 목록에서 제외된 카페와 약국도 명시 요청하면 기존 순서로 조회한다")
    void keepsLegacyCategoriesAvailableForExplicitQueries() {
        List<NearbyPlaceCategory> calledCategories = new ArrayList<>();
        NearbyPlaceQueryService service =
                service(complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)), query -> {
                    calledCategories.add(query.category());
                    return new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of());
                });

        service.getNearbyPlaces(
                501L,
                800,
                List.of(NearbyPlaceCategory.PHARMACY, NearbyPlaceCategory.CAFE, NearbyPlaceCategory.PHARMACY),
                5);

        assertThat(calledCategories).containsExactly(NearbyPlaceCategory.CAFE, NearbyPlaceCategory.PHARMACY);
    }

    @Test
    @DisplayName("provider category mismatch와 한 category 실패는 partial response 없이 전체 실패한다")
    void rejectsCategoryMismatchAndPartialProviderFailure() {
        NearbyPlaceCenterReader centerReader =
                complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109));
        NearbyPlaceQueryService mismatchService = service(
                centerReader, query -> new NearbyPlaceProviderResult(NearbyPlaceCategory.SCHOOL, 0, NOW, List.of()));
        assertThatThrownBy(() -> mismatchService.getNearbyPlaces(501L, 800, List.of(NearbyPlaceCategory.CAFE), 5))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class);

        AtomicInteger calls = new AtomicInteger();
        NearbyPlaceQueryService failureService = service(centerReader, query -> {
            NearbyPlaceCategory category = query.category();
            calls.incrementAndGet();
            if (category == NearbyPlaceCategory.RESTAURANT) {
                throw new NearbyPlaceProviderUnavailableException("provider failed");
            }
            return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
        });
        assertThatThrownBy(() -> failureService.getNearbyPlaces(
                        501L, 800, List.of(NearbyPlaceCategory.CAFE, NearbyPlaceCategory.RESTAURANT), 5))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("전체 provider 작업이 timeout되면 작업을 취소하고 503용 실패로 변환한다")
    void timesOutIncompleteProviderWork() {
        NearbyPlaceQueryService service = service(
                complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)),
                query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of()),
                command -> {},
                1);

        assertThatThrownBy(() -> service.getNearbyPlaces(501L, 800, List.of(NearbyPlaceCategory.CAFE), 5))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("provider 대기 thread interrupt를 보존하고 503용 실패로 변환한다")
    void preservesInterruptedStatus() {
        NearbyPlaceQueryService service = service(
                complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)),
                query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of()),
                command -> {},
                5_000);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.getNearbyPlaces(501L, 800, List.of(NearbyPlaceCategory.CAFE), 5))
                    .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("provider executor가 포화되면 작업을 queueing하지 않고 503용 실패로 변환한다")
    void rejectsSaturatedExecutor() {
        NearbyPlaceQueryService service = service(
                complexId -> Optional.of(new NearbyPlaceCenter(complexId, 37.321, 127.109)),
                query -> new NearbyPlaceProviderResult(query.category(), 0, NOW, List.of()),
                command -> {
                    throw new RejectedExecutionException("executor saturated");
                },
                5_000);

        assertThatThrownBy(() -> service.getNearbyPlaces(501L, 800, List.of(NearbyPlaceCategory.CAFE), 5))
                .isInstanceOf(NearbyPlaceProviderUnavailableException.class)
                .hasMessageNotContaining("executor saturated");
    }

    private NearbyPlaceQueryService service(NearbyPlaceCenterReader centerReader, NearbyPlaceProvider provider) {
        return service(centerReader, provider, Runnable::run, 5_000);
    }

    private NearbyPlaceQueryService service(
            NearbyPlaceCenterReader centerReader, NearbyPlaceProvider provider, Executor executor, long timeoutMillis) {
        return new NearbyPlaceQueryService(
                centerReader,
                provider,
                new NearbyPlaceExecutionOptions(
                        executor, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(timeoutMillis)));
    }

    private NearbyPlaceItem place(String id, String name, int distanceMeters) {
        return new NearbyPlaceItem(
                id,
                name,
                "음식점 > 카페",
                37.322,
                127.108,
                distanceMeters,
                "경기도 수원시",
                "경기도 수원시 도로명",
                "031-000-0000",
                "https://place.map.kakao.com/123456");
    }
}
