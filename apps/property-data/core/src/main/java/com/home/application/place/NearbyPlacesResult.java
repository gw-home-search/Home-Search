package com.home.application.place;

import java.time.Instant;
import java.util.List;

public record NearbyPlacesResult(
	Long complexId,
	NearbyPlacePoint center,
	int radiusMeters,
	Instant generatedAt,
	List<NearbyPlaceCategoryResult> categories
) {
}
