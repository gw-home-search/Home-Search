package com.home.domain.ingest.backfill;

/**
 * RTMS backfill job 전체 처리 상태를 나타낸다. 개별 chunk 집계를 job 관점으로 요약할 때 사용한다.
 */
public enum RtmsBackfillJobStatus {

	PLANNED("계획", "chunk가 준비되었지만 아직 실행이 시작되지 않은 상태"),
	RUNNING("실행 중", "하나 이상의 chunk가 실행 중이거나 남은 대기 chunk가 있는 상태"),
	COMPLETED("완료", "모든 chunk가 성공적으로 끝난 상태"),
	PARTIAL("부분 완료", "일부 chunk가 실패했지만 job 결과를 일부 확보한 상태"),
	FAILED("실패", "job 실행 결과를 신뢰하기 어려울 만큼 실패한 상태");

	private final String titleKo;
	private final String descriptionKo;

	RtmsBackfillJobStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	/**
	 * 한국어 짧은 제목을 반환한다.
	 */
	public String titleKo() {
		return titleKo;
	}

	/**
	 * 한국어 설명을 반환한다.
	 */
	public String descriptionKo() {
		return descriptionKo;
	}

	/**
	 * 더 이상 실행 중이 아닌 종료 상태인지 판단한다.
	 */
	public boolean isFinished() {
		return this == COMPLETED || this == PARTIAL || this == FAILED;
	}

	/**
	 * 모든 chunk가 성공적으로 끝난 job 상태인지 판단한다.
	 */
	public boolean isCompleted() {
		return this == COMPLETED;
	}
}
