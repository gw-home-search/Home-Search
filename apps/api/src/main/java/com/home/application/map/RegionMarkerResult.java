package com.home.application.map;

public record RegionMarkerResult(
	Long id,
	String name,
	Double lat,
	Double lng,
	Double trend
) {
}
