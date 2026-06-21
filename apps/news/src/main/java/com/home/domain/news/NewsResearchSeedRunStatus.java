package com.home.domain.news;

public enum NewsResearchSeedRunStatus {

	RUNNING("실행 중", "AI research seed 실행이 진행 중입니다."),
	SUCCEEDED("성공", "AI research seed 실행이 실패 없이 완료되었습니다."),
	PARTIAL("부분 성공", "일부 후보 실패 또는 반려가 있었지만 실행이 완료되었습니다."),
	FAILED("실패", "AI research seed 실행이 실패했습니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsResearchSeedRunStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isTerminal() {
		return this != RUNNING;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
