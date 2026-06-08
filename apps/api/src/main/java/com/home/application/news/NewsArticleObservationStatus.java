package com.home.application.news;

/**
 * 외부 뉴스 관측 row의 수집 처리 상태를 나타낸다. 뉴스 신호 파이프라인에서 중복, 제외, 실패를 구분해 감사한다.
 */
public enum NewsArticleObservationStatus {
	OBSERVED("관측", "뉴스 원천 row를 정상 관측했고 후속 관련성 평가 대상인 상태"),
	FEATURED("특징 추출", "관련 뉴스로 판단되어 신호 feature 추출까지 진행한 상태"),
	DUPLICATE("중복", "같은 source/source_key 또는 정규화 키로 이미 처리된 상태"),
	SKIPPED_IRRELEVANT("무관 제외", "부동산/주거 신호와 무관해 후속 처리를 제외한 상태"),
	FETCH_FAILED("조회 실패", "뉴스 원천 조회 단계에서 실패한 상태"),
	TERMS_BLOCKED("금칙어 차단", "정책상 처리하지 않을 용어 또는 원천 조건에 걸린 상태"),
	PARSE_FAILED("파싱 실패", "뉴스 payload를 필수 필드로 해석하지 못한 상태");

	private final String titleKo;
	private final String descriptionKo;

	NewsArticleObservationStatus(String titleKo, String descriptionKo) {
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
	 * 실패로 분류되는 관측 상태인지 판단한다.
	 */
	public boolean isFailure() {
		return this == FETCH_FAILED || this == PARSE_FAILED;
	}
}
