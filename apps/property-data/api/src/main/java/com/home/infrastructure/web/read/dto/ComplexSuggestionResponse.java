package com.home.infrastructure.web.read.dto;

import com.home.application.read.ComplexSuggestionResult;

public record ComplexSuggestionResponse(
	Long complexId,
	String complexName,
	Long parcelId,
	String address
) {

	public static ComplexSuggestionResponse from(ComplexSuggestionResult result) {
		return new ComplexSuggestionResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.address()
		);
	}
}
