package com.home.domain.news;

public enum CollectionRunMode {

	RUN_ONCE("단건 실행", "수동 one-keyword 수집 실행입니다."),
	DAILY("일일 실행", "예약 일일 수집 실행입니다.");

	private final String titleKo;
	private final String descriptionKo;

	CollectionRunMode(String titleKo, String descriptionKo) {
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
