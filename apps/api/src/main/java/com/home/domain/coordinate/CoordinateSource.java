package com.home.domain.coordinate;

/**
 * complex 표시 좌표의 저장 source를 나타낸다. 저장 값은 enum 상수명이며 public API 응답 필드로 노출하지 않는다.
 */
public enum CoordinateSource {

	BUILDING_FOOTPRINT("건물 footprint", "건물 footprint 후보로 확정한 단지 단위 표시 좌표"),
	PARCEL_FALLBACK("필지 fallback", "단지 단위 좌표가 없을 때 필지 좌표를 사용한 표시 좌표");

	private final String titleKo;
	private final String descriptionKo;

	CoordinateSource(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	/**
	 * 한국어 짧은 제목을 반환한다.
	 */
	public String titleKo() {
		return titleKo;
	}

	/**
	 * 한국어 설명을 반환한다.
	 */
	public String descriptionKo() {
		return descriptionKo;
	}

	/**
	 * DB에 저장되는 안정적인 source 값을 반환한다.
	 */
	public String storedValue() {
		return name();
	}

	/**
	 * 저장된 source 값과 현재 source가 같은지 판단한다.
	 */
	public boolean matches(String value) {
		return storedValue().equals(value);
	}

	/**
	 * 이 source가 건물 footprint 기반 표시 좌표인지 판단한다.
	 */
	public boolean isBuildingFootprint() {
		return this == BUILDING_FOOTPRINT;
	}
}
