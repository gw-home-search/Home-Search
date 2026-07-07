package com.home.application.map;

public record RegionMarkerQuery(
	Double swLat,
	Double swLng,
	Double neLat,
	Double neLng,
	String region
) {
}
