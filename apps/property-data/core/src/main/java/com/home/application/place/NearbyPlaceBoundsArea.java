package com.home.application.place;

import java.util.Objects;

public record NearbyPlaceBoundsArea(NearbyPlacePoint center, NearbyPlaceBounds bounds, int level)
        implements NearbyPlaceSearchArea {

    public NearbyPlaceBoundsArea {
        Objects.requireNonNull(center);
        Objects.requireNonNull(bounds);
    }
}
