package com.home.application.place;

import java.time.Instant;

public record ViewportNearbyPlacesResult(
        NearbyPlaceBounds bounds, int level, Instant generatedAt, ViewportNearbyPlaceCategoryResult category) {}
