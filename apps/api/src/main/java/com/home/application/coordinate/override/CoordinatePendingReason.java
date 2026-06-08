package com.home.application.coordinate.override;

/**
 * 좌표 운영 화면에서 수동 확인이 필요한 대기 사유를 나타낸다. API 응답에는 상수명만 유지하고 한글 설명은 내부 안내용이다.
 */
public enum CoordinatePendingReason {
	PNU_COORDINATE_MISSING("PNU 좌표 없음", "좌표 원천에서 PNU 기준 필지 좌표를 찾지 못한 상태"),
	SAME_PNU_MULTI_COMPLEX("동일 PNU 다중 단지", "같은 PNU에 여러 단지가 있어 단지별 표시 좌표 분리가 필요한 상태"),
	COMPLEX_DISPLAY_COORDINATE_MISSING("단지 표시 좌표 없음", "공개 지도 표시용 단지 좌표가 아직 확정되지 않은 상태");

	private final String titleKo;
	private final String descriptionKo;

	CoordinatePendingReason(String titleKo, String descriptionKo) {
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
}
