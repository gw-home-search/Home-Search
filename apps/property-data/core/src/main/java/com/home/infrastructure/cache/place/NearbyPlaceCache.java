package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlaceProviderResult;
import java.util.Optional;

public interface NearbyPlaceCache {

    Optional<NearbyPlaceProviderResult> find(NearbyPlaceCacheKey key);

    void store(NearbyPlaceCacheKey key, NearbyPlaceProviderResult result);
}
