package com.home.domain.news;

public enum SignalImpactDirection {

	up("상승", "대상 지표 상승 방향 신호입니다."),
	down("하락", "대상 지표 하락 방향 신호입니다."),
	mixed("혼합", "상승/하락 방향이 혼재합니다."),
	unknown("불명", "방향을 판단할 근거가 부족합니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalImpactDirection(String titleKo, String descriptionKo) {
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
