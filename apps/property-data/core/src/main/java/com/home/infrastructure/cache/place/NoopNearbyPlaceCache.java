package com.home.infrastructure.cache.place;

import java.util.Optional;

import com.home.application.place.NearbyPlaceProviderResult;

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
