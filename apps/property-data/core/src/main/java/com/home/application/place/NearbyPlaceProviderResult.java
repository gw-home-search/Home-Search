package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;

public record NearbyPlaceProviderResult(
        NearbyPlaceCategory category, int matchedCount, Instant retrievedAt, List<NearbyPlaceItem> places) {}
