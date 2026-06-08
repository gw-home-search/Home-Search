package com.home.application.news;

/**
 * 뉴스 관측 row의 부동산 신호 관련성 결정을 나타낸다. feature confidence 보정과 관측 상태 갱신의 기준으로 사용한다.
 */
public enum NewsArticleRelevanceDecisionType {
	KEEP("유지", "부동산 지도/거래 신호로 활용할 수 있어 후속 feature 추출을 진행하는 결정"),
	REVIEW("검토", "자동 활용은 보류하지만 운영 검토나 낮은 신뢰도 feature 후보로 남기는 결정"),
	SKIP_IRRELEVANT("무관 제외", "프로젝트 신호와 무관하므로 관측 row를 제외 처리하는 결정");

	private final String titleKo;
	private final String descriptionKo;

	NewsArticleRelevanceDecisionType(String titleKo, String descriptionKo) {
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
	 * 유지 결정인지 판단한다.
	 */
	public boolean isKeep() {
		return this == KEEP;
	}

	/**
	 * 운영 검토 결정인지 판단한다.
	 */
	public boolean isReview() {
		return this == REVIEW;
	}

	/**
	 * 무관 제외 결정인지 판단한다.
	 */
	public boolean isSkipIrrelevant() {
		return this == SKIP_IRRELEVANT;
	}

	/**
	 * 뉴스 feature confidence에 더할 결정별 보정값을 반환한다.
	 */
	public double signalConfidenceBonus() {
		return switch (this) {
			case KEEP -> 0.10;
			case REVIEW -> 0.02;
			case SKIP_IRRELEVANT -> 0.0;
		};
	}
}
