package com.home.application.place;

import java.time.Instant;
import java.util.List;

import com.home.domain.place.NearbyPlaceCategory;

public record NearbyPlaceProviderResult(
	NearbyPlaceCategory category,
	int matchedCount,
	Instant retrievedAt,
	List<NearbyPlaceItem> places
) {
}
