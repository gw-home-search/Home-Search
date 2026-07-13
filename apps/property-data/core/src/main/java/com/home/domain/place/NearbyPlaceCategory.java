package com.home.domain.place;

public enum NearbyPlaceCategory {
	CAFE("카페", "카페 장소"),
	RESTAURANT("음식점", "음식점 장소"),
	CONVENIENCE_STORE("편의점", "편의점 장소"),
	HOSPITAL("병원", "병원 장소"),
	PHARMACY("약국", "약국 장소"),
	SCHOOL("학교", "학교 장소");

	private final String titleKo;
	private final String descriptionKo;

	NearbyPlaceCategory(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
