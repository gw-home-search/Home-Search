package com.home.domain.ingest.backfill;

/**
 * RTMS backfill job의 월/지역 chunk 처리 상태를 나타낸다. job 집계와 재시도 후보 선별의 기준으로 사용한다.
 */
public enum RtmsBackfillChunkStatus {

	PENDING("대기", "아직 실행되지 않은 backfill chunk"),
	RUNNING("실행 중", "worker가 claim해서 수집을 진행 중인 chunk"),
	COMPLETED("완료", "대상 범위 수집이 성공적으로 끝난 chunk"),
	PARTIAL("부분 완료", "일부 수집은 성공했지만 실패나 누락이 남은 chunk"),
	FAILED("실패", "수집 중 오류로 재시도 또는 원인 확인이 필요한 chunk"),
	BLOCKED("차단", "선행 조건이나 중복 실행 방지 조건으로 실행할 수 없는 chunk"),
	SKIPPED("제외", "현재 정책상 실행하지 않기로 한 chunk");

	private final String titleKo;
	private final String descriptionKo;

	RtmsBackfillChunkStatus(String titleKo, String descriptionKo) {
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
	 * worker가 더 이상 처리 중이지 않은 종료 상태인지 판단한다.
	 */
	public boolean isFinished() {
		return this == COMPLETED || this == PARTIAL || this == FAILED || this == BLOCKED || this == SKIPPED;
	}

	/**
	 * 대상 범위 수집이 성공적으로 끝난 chunk 상태인지 판단한다.
	 */
	public boolean isCompleted() {
		return this == COMPLETED;
	}

	/**
	 * 일부 수집만 성공해 partial 처리해야 하는 chunk 상태인지 판단한다.
	 */
	public boolean isPartial() {
		return this == PARTIAL;
	}

	/**
	 * 운영자가 원인을 확인해야 하는 문제 상태인지 판단한다.
	 */
	public boolean isProblem() {
		return this == PARTIAL || this == FAILED || this == BLOCKED;
	}
}
