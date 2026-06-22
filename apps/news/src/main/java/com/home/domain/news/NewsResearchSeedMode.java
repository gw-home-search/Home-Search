package com.home.domain.news;

public enum NewsResearchSeedMode {

	GENERATE_NOTES("note 생성", "AI 후보를 Obsidian 검수 note로 생성합니다."),
	GENERATE_CSV_NOTES("CSV note 생성", "BigKinds CSV 후보를 Obsidian 검수 note로 생성합니다."),
	GENERATE_CSV_SHORTLIST("CSV shortlist 생성", "BigKinds CSV 후보를 월별 metadata-only shortlist로 생성합니다."),
	IMPORT_APPROVED("승인 import", "MANUAL_APPROVED note만 database로 import합니다."),
	DRY_RUN("dry run", "외부 write 없이 설정과 입력만 검증합니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsResearchSeedMode(String titleKo, String descriptionKo) {
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
