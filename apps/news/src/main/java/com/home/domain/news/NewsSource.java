package com.home.domain.news;

public enum NewsSource {

	NAVER_NEWS_SEARCH("Naver News Search", "Naver News Search API metadata source입니다.");

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
