package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlaceProviderResult;
import java.util.Optional;

public final class NoopNearbyPlaceCache implements NearbyPlaceCache {

    @Override
    public Optional<NearbyPlaceProviderResult> find(NearbyPlaceCacheKey key) {
        return Optional.empty();
    }

    @Override
    public void store(NearbyPlaceCacheKey key, NearbyPlaceProviderResult result) {
        // Intentionally disabled while the quota guard remains mandatory.
    }
}
