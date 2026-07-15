package com.home.infrastructure.web.read.dto;

import com.home.application.read.SearchComplexResult;

public record SearchComplexResponse(
	Long complexId,
	String complexName,
	Long parcelId,
	Double latitude,
	Double longitude,
	String address
) {

	public static SearchComplexResponse from(SearchComplexResult result) {
		return new SearchComplexResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.latitude(),
			result.longitude(),
			result.address()
		);
	}
}
