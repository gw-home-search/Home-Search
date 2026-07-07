package com.home.infrastructure.web.read.dto;

public record ComplexSuggestionResponse(
	Long complexId,
	String complexName,
	Long parcelId,
	String address
) {
}
