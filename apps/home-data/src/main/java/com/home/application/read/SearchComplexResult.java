package com.home.application.read;

public record SearchComplexResult(
	Long complexId,
	String complexName,
	Long parcelId,
	Double latitude,
	Double longitude,
	String address
) {
}
