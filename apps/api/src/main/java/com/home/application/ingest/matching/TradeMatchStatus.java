package com.home.application.ingest.matching;

/**
 * raw 거래 row가 내부 단지와 매칭된 결과를 나타낸다. failed match evidence의 queryable 상태로 보존된다.
 */
public enum TradeMatchStatus {
	MATCHED("매칭", "PNU와 단지명이 안전하게 내부 단지로 연결된 상태"),
	MATCHED_NAME_VARIANT("이름 변형 매칭", "PNU는 일치하고 단지명 표기 차이를 허용해 연결한 상태"),
	PNU_CONFLICT("PNU 충돌", "원천 PNU와 내부 후보 PNU가 충돌한 상태"),
	NAME_CONFLICT("이름 충돌", "PNU 후보는 있으나 단지명 근거가 충돌한 상태"),
	AMBIGUOUS("모호", "복수 후보 중 하나를 안전하게 선택할 수 없는 상태"),
	PNU_UNAVAILABLE("PNU 없음", "매칭에 필요한 PNU를 생성하거나 조회할 수 없는 상태"),
	UNMATCHED("미매칭", "내부 단지 후보를 찾지 못한 상태");

	private final String titleKo;
	private final String descriptionKo;

	TradeMatchStatus(String titleKo, String descriptionKo) {
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
	 * normalized trade 생성이 가능한 매칭 성공 상태인지 판단한다.
	 */
	public boolean isMatched() {
		return this == MATCHED || this == MATCHED_NAME_VARIANT;
	}
}
