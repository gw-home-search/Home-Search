package com.home.domain.news;

public enum NewsSource {

	NAVER_NEWS_SEARCH("Naver News Search", "Naver News Search API metadata source입니다."),
	AI_ASSISTED_WEB_RESEARCH("AI 보조 웹 리서치", "OpenAI web search로 후보를 찾고 사람이 승인한 historical seed source입니다."),
	BIGKINDS_CSV("BigKinds CSV", "BigKinds licensed historical CSV export로 후보를 찾고 사람이 승인한 historical seed source입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsSource(String titleKo, String descriptionKo) {
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
