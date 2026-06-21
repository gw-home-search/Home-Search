package com.home.domain.news;

public enum NewsModelDatasetTier {

	EXPERIMENTAL_SEED("실험 seed", "모델 효능 검증용 사람이 승인한 historical seed입니다."),
	OBSERVED_SIGNAL("관측 signal", "Home Search가 실시간으로 직접 관측한 production-safe signal입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsModelDatasetTier(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isProductionObserved() {
		return this == OBSERVED_SIGNAL;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
