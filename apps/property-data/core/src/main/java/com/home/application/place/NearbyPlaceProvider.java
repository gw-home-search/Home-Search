package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;

@FunctionalInterface
public interface NearbyPlaceProvider {

    NearbyPlaceProviderResult search(NearbyPlaceProviderQuery query);

    default NearbyPlaceProviderResult search(NearbyPlacePoint center, int radiusMeters, NearbyPlaceCategory category) {
        return search(new NearbyPlaceProviderQuery(new NearbyPlaceRadiusArea(center, radiusMeters), category));
    }
}
