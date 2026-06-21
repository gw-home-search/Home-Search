package com.home.domain.news;

public enum SignalScoreSignalStrength {

	STRONG("강함", "가격, 전세, 거래량, 공급, 위험 지표로 계량화하기 좋은 후보입니다."),
	MEDIUM("중간", "계량 feature 가능성은 있으나 근거가 약한 후보입니다."),
	WEAK("약함", "계량 feature로 쓰기 어려운 후보입니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalScoreSignalStrength(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isStrong() {
		return this == STRONG;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
