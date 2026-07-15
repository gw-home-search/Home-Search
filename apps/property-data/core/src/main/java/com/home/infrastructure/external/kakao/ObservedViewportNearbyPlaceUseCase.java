package com.home.infrastructure.external.kakao;

import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.ViewportNearbyPlaceQueryService;
import com.home.application.place.ViewportNearbyPlaceUseCase;
import com.home.application.place.ViewportNearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
final class ObservedViewportNearbyPlaceUseCase implements ViewportNearbyPlaceUseCase {

    private final ViewportNearbyPlaceQueryService delegate;
    private final MeterRegistry meterRegistry;

    ObservedViewportNearbyPlaceUseCase(ViewportNearbyPlaceQueryService delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ViewportNearbyPlacesResult getNearbyPlaces(
            NearbyPlaceBounds bounds, Integer level, NearbyPlaceCategory category) {
        String result = "success";
        try {
            return delegate.getNearbyPlaces(bounds, level, category);
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            meterRegistry
                    .counter("home.search.nearby.place.requests", "scope", "viewport", "result", result)
                    .increment();
        }
    }
}
