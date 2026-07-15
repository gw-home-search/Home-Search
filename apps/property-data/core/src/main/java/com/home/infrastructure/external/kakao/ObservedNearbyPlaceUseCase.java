package com.home.infrastructure.external.kakao;

import com.home.application.place.NearbyPlaceQueryService;
import com.home.application.place.NearbyPlaceUseCase;
import com.home.application.place.NearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
final class ObservedNearbyPlaceUseCase implements NearbyPlaceUseCase {

    private final NearbyPlaceQueryService delegate;
    private final MeterRegistry meterRegistry;

    ObservedNearbyPlaceUseCase(NearbyPlaceQueryService delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public NearbyPlacesResult getNearbyPlaces(
            Long complexId, Integer radiusMeters, List<NearbyPlaceCategory> categories, Integer limitPerCategory) {
        String result = "success";
        try {
            return delegate.getNearbyPlaces(complexId, radiusMeters, categories, limitPerCategory);
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            meterRegistry
                    .counter("home.search.nearby.place.requests", "scope", "complex", "result", result)
                    .increment();
        }
    }
}
