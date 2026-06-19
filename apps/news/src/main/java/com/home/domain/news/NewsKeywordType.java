package com.home.domain.news;

public enum NewsKeywordType {

	TOPIC("주제", "정책, 공급, 금리 등 주제 keyword입니다."),
	REGION("지역", "지역명 기반 keyword입니다."),
	COMPLEX("단지", "아파트 단지명 기반 keyword입니다."),
	ALIAS("별칭", "검색 보강용 별칭 keyword입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsKeywordType(String titleKo, String descriptionKo) {
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
