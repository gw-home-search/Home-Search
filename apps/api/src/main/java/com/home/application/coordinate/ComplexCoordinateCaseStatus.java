package com.home.application.coordinate;

/**
 * 동일 필지 다중 단지의 표시 좌표 예외 처리 상태를 나타낸다. 저장 값은 enum 상수명이며 지도 API 응답 필드로 노출하지 않는다.
 */
public enum ComplexCoordinateCaseStatus {

	PENDING("대기", "표시 좌표 예외 처리가 필요해 해결 대기 중인 상태"),
	RESOLVED("해결", "건물 footprint 등으로 단지별 표시 좌표를 확정한 상태"),
	AMBIGUOUS("모호", "후보가 겹치거나 여러 후보를 안전하게 좁힐 수 없는 상태"),
	UNAVAILABLE("불가", "필요한 원천 좌표 또는 건물 후보를 확보할 수 없는 상태"),
	FAILED("실패", "처리 중 예외나 외부 검증 실패로 재시도 또는 운영 확인이 필요한 상태"),
	SKIPPED("제외", "좌표 예외 처리가 필요 없는 관계로 분류되어 건너뛴 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexCoordinateCaseStatus(String titleKo, String descriptionKo) {
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
	 * 처리 대기 상태인지 판단한다.
	 */
	public boolean isPending() {
		return this == PENDING;
	}

	/**
	 * 좌표 예외가 해결된 상태인지 판단한다.
	 */
	public boolean isResolved() {
		return this == RESOLVED;
	}

	/**
	 * 후보가 모호해 자동 확정할 수 없는 상태인지 판단한다.
	 */
	public boolean isAmbiguous() {
		return this == AMBIGUOUS;
	}

	/**
	 * 필요한 원천 데이터가 없어 해결할 수 없는 상태인지 판단한다.
	 */
	public boolean isUnavailable() {
		return this == UNAVAILABLE;
	}

	/**
	 * 처리 실패 상태인지 판단한다.
	 */
	public boolean isFailed() {
		return this == FAILED;
	}

	/**
	 * 예외 처리를 건너뛴 상태인지 판단한다.
	 */
	public boolean isSkipped() {
		return this == SKIPPED;
	}

	/**
	 * 다중 단지 필지에서 낮은 신뢰도의 필지 좌표 fallback을 써야 하는 미해결 상태인지 판단한다.
	 */
	public boolean usesUnresolvedFallbackConfidence() {
		return isAmbiguous() || isUnavailable() || isFailed();
	}
}
