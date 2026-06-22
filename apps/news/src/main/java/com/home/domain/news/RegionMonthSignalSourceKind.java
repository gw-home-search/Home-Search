package com.home.domain.news;

public enum RegionMonthSignalSourceKind {

	BIGKINDS_CSV("BigKinds CSV", "BigKinds historical CSV metadata aggregate입니다."),
	AGENT_WEB_RESEARCH("Agent web research", "agent가 작성한 metadata-only web research aggregate입니다.");

	private final String titleKo;
	private final String descriptionKo;

	RegionMonthSignalSourceKind(String titleKo, String descriptionKo) {
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
