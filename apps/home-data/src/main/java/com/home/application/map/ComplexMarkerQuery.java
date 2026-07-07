package com.home.application.map;

public record ComplexMarkerQuery(
	Double swLat,
	Double swLng,
	Double neLat,
	Double neLng,
	Integer pyeongMin,
	Integer pyeongMax,
	Double priceEokMin,
	Double priceEokMax,
	Integer ageMin,
	Integer ageMax,
	Long unitMin,
	Long unitMax
) {
}
