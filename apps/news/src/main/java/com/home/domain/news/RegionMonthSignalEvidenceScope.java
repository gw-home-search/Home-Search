package com.home.domain.news;

public enum RegionMonthSignalEvidenceScope {

	DIRECT("직접", "bucket과 signal month에 직접 대응하는 metadata evidence입니다."),
	INHERITED("상속", "상위 지역이나 전국 evidence를 낮은 confidence로 상속한 metadata evidence입니다.");

	private final String titleKo;
	private final String descriptionKo;

	RegionMonthSignalEvidenceScope(String titleKo, String descriptionKo) {
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
