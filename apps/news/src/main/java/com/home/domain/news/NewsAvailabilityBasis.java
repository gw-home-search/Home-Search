package com.home.domain.news;

public enum NewsAvailabilityBasis {

	AI_ASSISTED_RESEARCH_SEED("AI historical seed", "2017-01-01부터 2026-05-31까지 AI가 발굴하고 사람이 승인한 seed입니다."),
	AI_ASSISTED_TRANSITION_SEED("AI transition seed", "정식 실시간 관측 전환기 공백을 보완하는 사람이 승인한 seed입니다."),
	LICENSED_HISTORICAL_EXPORT("Licensed historical export", "라이선스가 있는 historical provider export에서 사람이 승인한 seed입니다."),
	REALTIME_OBSERVED("실시간 관측", "Home Search가 provider API로 직접 관측한 기사입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsAvailabilityBasis(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isAiAssistedSeed() {
		return this == AI_ASSISTED_RESEARCH_SEED || this == AI_ASSISTED_TRANSITION_SEED;
	}

	public boolean isHistoricalSeed() {
		return isAiAssistedSeed() || this == LICENSED_HISTORICAL_EXPORT;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
