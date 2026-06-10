package com.home.domain.news;

/**
 * 뉴스 일일 수집 pipeline과 keyword 실행 이력에 저장되는 처리 상태입니다.
 */
public enum NewsCollectionRunStatus {

	STARTED("시작", "뉴스 수집 실행이 시작되었지만 아직 최종 결과가 확정되지 않은 상태"),
	SKIPPED("건너뜀", "실행 대상 keyword가 없어 후속 처리를 건너뛴 상태"),
	COMPLETED("완료", "모든 실행 대상과 후속 처리가 완료된 상태"),
	PARTIAL("부분 실패", "일부 keyword 또는 후속 단계가 실패했지만 처리 가능한 항목은 완료한 상태"),
	FAILED("실패", "실행 대상 또는 후속 처리 전체가 실패한 상태");

	private final String titleKo;
	private final String descriptionKo;

	NewsCollectionRunStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}

	public boolean isCompleted() {
		return this == COMPLETED;
	}

	public boolean isFailure() {
		return this == FAILED;
	}

	public boolean isTerminal() {
		return this != STARTED;
	}
}
