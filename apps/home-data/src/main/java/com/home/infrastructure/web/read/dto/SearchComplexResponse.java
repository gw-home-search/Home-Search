package com.home.infrastructure.web.read.dto;

public record SearchComplexResponse(
	Long complexId,
	String complexName,
	Long parcelId,
	Double latitude,
	Double longitude,
	String address
) {
}
