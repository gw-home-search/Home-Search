package com.home.domain.news;

public enum RegionMonthSignalRunMode {

	GENERATE_CSV_REGION_MONTH_SIGNALS("CSV aggregate 생성", "BigKinds CSV에서 월별 region aggregate JSONL을 생성합니다."),
	VALIDATE_WEB_REGION_MONTH_SIGNALS("web JSONL 검증", "agent web research JSONL을 검증합니다."),
	IMPORT_REGION_MONTH_SIGNALS("aggregate import", "CSV와 web aggregate JSONL을 database에 upsert합니다."),
	EXPORT_REGION_MONTH_SIGNALS("Obsidian export", "database aggregate를 Obsidian 월별 note로 내보냅니다.");

	private final String titleKo;
	private final String descriptionKo;

	RegionMonthSignalRunMode(String titleKo, String descriptionKo) {
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
