package com.home.application.ingest.run;

import java.util.List;

/**
 * RTMS ingest run의 최종 결과 상태를 나타낸다. 수집 리포트의 상태 필터와 집계 순서를 고정한다.
 */
public enum RtmsIngestRunStatus {
	COMPLETED("완료", "대상 수집 실행이 성공적으로 종료된 상태"),
	PARTIAL("부분 완료", "일부 row 또는 page 처리 실패가 있었지만 부분 결과가 남은 상태"),
	FAILED("실패", "실행 자체가 실패해 결과를 신뢰하기 어려운 상태");

	private final String titleKo;
	private final String descriptionKo;

	RtmsIngestRunStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	/**
	 * 리포트에서 표시할 전체 상태 순서를 반환한다.
	 */
	public static List<RtmsIngestRunStatus> all() {
		return List.of(COMPLETED, PARTIAL, FAILED);
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
	 * 운영 확인이 필요한 문제 상태인지 판단한다.
	 */
	public boolean isProblem() {
		return this == PARTIAL || this == FAILED;
	}
}
