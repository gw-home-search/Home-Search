package com.home.application.ingest;

/**
 * 단지 메타데이터 보강 상태를 나타낸다. 저장 값은 enum 상수명이며, 보강 결과 집계와 재시도 정책의 기준으로 사용한다.
 */
public enum ComplexMetadataStatus {

	PENDING("대기", "메타데이터 보강이 아직 처리되지 않은 상태"),
	RESOLVED("완료", "공개 지도와 내부 검증에 필요한 핵심 메타데이터가 모두 확보된 상태"),
	PARTIAL("부분 완료", "일부 메타데이터를 확보했지만 핵심 필드가 모두 채워지지는 않은 상태"),
	AMBIGUOUS("모호", "복수 원천 후보나 원천 간 충돌로 값을 확정할 수 없는 상태"),
	UNAVAILABLE("불가", "원천 후보가 없거나 입력 부족으로 값을 가져올 수 없는 상태"),
	FAILED("실패", "외부 호출 또는 처리 중 실패가 발생한 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexMetadataStatus(String titleKo, String descriptionKo) {
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
	 * 보강 메타데이터 payload가 필수인 상태인지 판단한다.
	 */
	public boolean requiresMetadataPayload() {
		return isResolvedLike();
	}

	/**
	 * 핵심 필드가 모두 채워져야 하는 완료 상태인지 판단한다.
	 */
	public boolean requiresCompleteCriticalFields() {
		return isResolved();
	}

	/**
	 * 완료 또는 부분 완료처럼 메타데이터를 보유하는 상태인지 판단한다.
	 */
	public boolean isResolvedLike() {
		return isResolved() || isPartial();
	}

	/**
	 * 완료 상태인지 판단한다.
	 */
	public boolean isResolved() {
		return this == RESOLVED;
	}

	/**
	 * 부분 완료 상태인지 판단한다.
	 */
	public boolean isPartial() {
		return this == PARTIAL;
	}

	/**
	 * 모호 상태인지 판단한다.
	 */
	public boolean isAmbiguous() {
		return this == AMBIGUOUS;
	}

	/**
	 * 원천 불가 상태인지 판단한다.
	 */
	public boolean isUnavailable() {
		return this == UNAVAILABLE;
	}

	/**
	 * 실패 상태인지 판단한다.
	 */
	public boolean isFailed() {
		return this == FAILED;
	}
}
