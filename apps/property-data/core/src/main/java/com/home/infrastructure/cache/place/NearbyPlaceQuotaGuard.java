package com.home.infrastructure.cache.place;

@FunctionalInterface
public interface NearbyPlaceQuotaGuard {

	void acquire();
}
