package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;

@FunctionalInterface
public interface NearbyPlaceProvider {

    NearbyPlaceProviderResult search(NearbyPlacePoint center, int radiusMeters, NearbyPlaceCategory category);
}
