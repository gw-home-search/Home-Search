package com.home.domain.news;

public enum NewsModelDatasetTier {

	EXPERIMENTAL_SEED("실험 seed", "모델 개발용 aggregate seed입니다."),
	OBSERVED_SIGNAL("관측 signal", "직접 관측한 production-safe signal입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsModelDatasetTier(String titleKo, String descriptionKo) {
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
