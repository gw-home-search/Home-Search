package com.home.infrastructure.web.map.dto;

public record RegionMarkerResponse(
	Long id,
	String name,
	Double lat,
	Double lng,
	Double trend,
	Long unitCntSum
) {
}
