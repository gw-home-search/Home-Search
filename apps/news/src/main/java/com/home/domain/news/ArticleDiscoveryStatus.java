package com.home.domain.news;

public enum ArticleDiscoveryStatus {

	NEW_OBSERVATION("신규 관측", "처음 관측된 기사입니다."),
	DUPLICATE_OBSERVATION("중복 관측", "이미 관측된 기사입니다."),
	FEATURE_CREATED("시그널 생성", "관측 기사에서 시그널 feature가 생성되었습니다."),
	FEATURE_SKIPPED("시그널 생략", "중복 또는 무관 판정으로 feature 생성을 생략했습니다."),
	FAILED("실패", "기사 처리 중 실패했습니다.");

	private final String titleKo;
	private final String descriptionKo;

	ArticleDiscoveryStatus(String titleKo, String descriptionKo) {
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
