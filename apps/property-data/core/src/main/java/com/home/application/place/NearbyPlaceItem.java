package com.home.application.place;

public record NearbyPlaceItem(
	String placeId,
	String name,
	String categoryDetail,
	double lat,
	double lng,
	int distanceMeters,
	String address,
	String roadAddress,
	String phone,
	String placeUrl
) {
}
