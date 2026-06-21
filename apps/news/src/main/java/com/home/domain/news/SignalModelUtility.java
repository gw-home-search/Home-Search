package com.home.domain.news;

public enum SignalModelUtility {

	HIGH("높음", "가격 예측 feature로 바로 쓸 수 있는 후보입니다."),
	MEDIUM("중간", "feature 후보이나 추가 검수가 필요한 후보입니다."),
	LOW("낮음", "가격 예측 feature로 쓰기 어려운 후보입니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalModelUtility(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isHigh() {
		return this == HIGH;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
