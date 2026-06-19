package com.home.domain.news;

public enum KeywordCadence {

	MANUAL("수동", "수동 실행 때만 사용하는 keyword입니다."),
	DAILY("일일", "일일 수집 대상 keyword입니다."),
	WEEKLY("주간", "주간 수집 대상 keyword입니다."),
	MONTHLY("월간", "월간 수집 대상 keyword입니다.");

	private final String titleKo;
	private final String descriptionKo;

	KeywordCadence(String titleKo, String descriptionKo) {
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
