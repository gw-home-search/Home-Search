package com.home.domain.news;

public enum NotificationStatus {

	NOT_REQUESTED("요청 없음", "알림 전송을 요청하지 않았습니다."),
	SUCCEEDED("성공", "알림 전송이 성공했습니다."),
	FAILED("실패", "알림 전송이 실패했습니다.");

	private final String titleKo;
	private final String descriptionKo;

	NotificationStatus(String titleKo, String descriptionKo) {
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
