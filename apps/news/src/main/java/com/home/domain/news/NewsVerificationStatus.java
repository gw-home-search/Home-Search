package com.home.domain.news;

public enum NewsVerificationStatus {

	NEEDS_REVIEW("검수 필요", "사람 검수가 필요한 후보입니다."),
	MANUAL_APPROVED("수동 승인", "사람이 URL, 날짜, 언론사, 지역, topic을 확인한 후보입니다."),
	REJECTED("반려", "사람 검수에서 제외한 후보입니다."),
	SYSTEM_ACCEPTED("시스템 승인", "provider 관측과 relevance gate로 자동 승인한 기사입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsVerificationStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isImportableManualApproval() {
		return this == MANUAL_APPROVED;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
