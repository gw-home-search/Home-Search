package com.home.domain.news;

/**
 * 뉴스 수집 keyword의 출처와 사용 목적을 구분하는 저장 분류입니다.
 */
public enum NewsCollectionKeywordType {

	TOPIC("주제", "정책, 금리, 공급처럼 지역이나 단지와 독립적인 뉴스 주제 keyword"),
	REGION("지역", "region table 또는 지역 운영 기준에서 파생된 keyword"),
	COMPLEX("단지", "complex table 또는 단지 별칭에서 파생된 keyword"),
	ALIAS("별칭", "RAG 또는 Tool 조회에서 재사용할 수 있는 사람이 관리하는 별칭 keyword");

	private final String titleKo;
	private final String descriptionKo;

	NewsCollectionKeywordType(String titleKo, String descriptionKo) {
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
