package com.home.application.place;

import java.util.Objects;

public record NearbyPlaceRadiusArea(NearbyPlacePoint center, int radiusMeters) implements NearbyPlaceSearchArea {

    public NearbyPlaceRadiusArea {
        Objects.requireNonNull(center);
        if (radiusMeters < 1) {
            throw new IllegalArgumentException("radiusMeters must be positive");
        }
    }
}
