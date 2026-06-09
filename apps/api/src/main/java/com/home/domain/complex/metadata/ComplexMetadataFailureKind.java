package com.home.domain.complex.metadata;

/**
 * 단지 메타데이터 보강 실패의 원인 분류를 나타낸다. 재시도 정책은 이 enum의 의미 메서드를 기준으로 판단한다.
 */
public enum ComplexMetadataFailureKind {

	TRANSIENT("일시 실패", "외부 API 장애, 네트워크 오류처럼 재시도로 회복될 수 있는 실패"),
	PERMANENT("영구 실패", "현재 입력이나 원천 조건에서는 재시도로 회복되기 어려운 실패"),
	SOURCE_MISSING("원천 없음", "외부 원천에서 대상 단지 후보를 찾지 못한 상태"),
	INPUT_INSUFFICIENT("입력 부족", "PNU, 주소 등 조회에 필요한 입력이 부족한 상태"),
	AMBIGUOUS("모호", "복수 후보나 원천 간 충돌로 안전하게 하나를 선택할 수 없는 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexMetadataFailureKind(String titleKo, String descriptionKo) {
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
	 * 원천 후보 부재로 인한 실패인지 판단한다.
	 */
	public boolean isSourceMissing() {
		return this == SOURCE_MISSING;
	}

	/**
	 * 일시 실패라 재시도 backoff를 적용할 수 있는지 판단한다.
	 */
	public boolean isTransient() {
		return this == TRANSIENT;
	}
}
