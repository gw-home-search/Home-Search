package com.home.infrastructure.cache.place;

import java.util.Optional;

import com.home.application.place.NearbyPlaceProviderResult;

public interface NearbyPlaceCache {

	Optional<NearbyPlaceProviderResult> find(NearbyPlaceCacheKey key);

	void store(NearbyPlaceCacheKey key, NearbyPlaceProviderResult result);
}
