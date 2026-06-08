package com.home.application.ingest.raw;

/**
 * 원천 거래 row의 수집 및 정규화 처리 상태를 나타낸다. raw 저장 이후의 감사와 재처리 대상을 구분하는 내부 상태다.
 */
public enum RawTradeIngestStatus {
	RECEIVED("수신", "원천 payload를 저장했지만 아직 정규화 처리가 끝나지 않은 상태"),
	NORMALIZED("정규화 완료", "공개 조회에 사용할 normalized trade row를 생성한 상태"),
	DUPLICATE("중복", "raw 증거는 저장했지만 중복 규칙에 따라 normalized trade를 추가하지 않은 상태"),
	CANCELED("취소", "원천 취소 row로 인해 연결된 normalized trade를 공개 표시에서 제외한 상태"),
	MATCH_FAILED("매칭 실패", "단지 매칭 실패가 queryable evidence로 남아 있는 상태"),
	PARSE_FAILED("파싱 실패", "원천 payload를 필수 거래 필드로 해석하지 못한 상태"),
	SKIPPED_INVALID("유효하지 않아 제외", "저장 후 처리 대상에서 제외할 수 있는 유효하지 않은 row 상태");

	private final String titleKo;
	private final String descriptionKo;

	RawTradeIngestStatus(String titleKo, String descriptionKo) {
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
	 * 추가 정규화 작업이 끝난 상태인지 판단한다.
	 */
	public boolean isTerminal() {
		return this != RECEIVED;
	}
}
