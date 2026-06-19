package com.home.domain.news;

public enum CollectionRunStatus {

	RUNNING("실행 중", "수집 실행이 진행 중입니다."),
	SUCCEEDED("성공", "수집 실행이 실패 없이 완료되었습니다."),
	PARTIAL("부분 성공", "일부 항목 실패가 있었지만 실행이 완료되었습니다."),
	FAILED("실패", "실행 단위가 실패했습니다.");

	private final String titleKo;
	private final String descriptionKo;

	CollectionRunStatus(String titleKo, String descriptionKo) {
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
