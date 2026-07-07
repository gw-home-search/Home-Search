package com.home.application.read;

public record ComplexSuggestionResult(
	Long complexId,
	String complexName,
	Long parcelId,
	String address
) {
}
