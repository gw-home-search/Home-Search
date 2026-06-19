package com.home.domain.news;

public enum NewsObservationStatus {

	OBSERVED("관측", "기사 metadata가 저장되었습니다."),
	FEATURED("시그널 생성됨", "기사 관측에서 feature가 생성되었습니다."),
	SKIPPED_IRRELEVANT("무관 생략", "수집됐지만 시그널 대상이 아닙니다."),
	FETCH_FAILED("수집 실패", "provider 호출 또는 응답 수집이 실패했습니다."),
	PARSE_FAILED("파싱 실패", "provider metadata 파싱이 실패했습니다."),
	TERMS_BLOCKED("약관 차단", "source policy상 추가 수집이 차단되었습니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsObservationStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isCleanupEligibleAfterRetention() {
		return this == FEATURED || this == SKIPPED_IRRELEVANT || this == TERMS_BLOCKED;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
