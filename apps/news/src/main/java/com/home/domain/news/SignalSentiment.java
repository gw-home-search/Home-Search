package com.home.domain.news;

public enum SignalSentiment {

	positive("긍정", "가격 또는 시장 여건에 긍정적인 신호입니다."),
	neutral("중립", "뚜렷한 긍정/부정 방향이 없습니다."),
	negative("부정", "가격 또는 시장 여건에 부정적인 신호입니다."),
	mixed("혼합", "긍정/부정 신호가 혼재합니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalSentiment(String titleKo, String descriptionKo) {
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
