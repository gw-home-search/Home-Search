package com.home.domain.complex.relation;

/**
 * 단지 관계 판정의 신뢰도를 나타낸다. 저장되는 값은 enum 상수명이며, 한글 설명은 내부 검토와 리포트용 메타데이터로만 사용한다.
 */
public enum ComplexRelationConfidence {

	HIGH("높음", "거래 기간과 보조 근거가 충분해 자동 판정을 신뢰할 수 있는 상태"),
	MEDIUM("보통", "일부 근거는 있으나 운영 검토 여지를 남겨야 하는 상태"),
	LOW("낮음", "휴리스틱 근거만 있어 보수적으로 참고해야 하는 상태"),
	NONE("없음", "판정 근거가 부족하거나 관계 유형이 확정되지 않은 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexRelationConfidence(String titleKo, String descriptionKo) {
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
