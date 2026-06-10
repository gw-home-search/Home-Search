package com.home.domain.news;

/**
 * 뉴스 수집 결과 알림의 Hermes 전송 상태입니다. Pipeline 성공 여부와 분리해 저장합니다.
 */
public enum NewsCollectionNotificationStatus {

	NOT_REQUESTED("요청 없음", "알림 전송이 설정되지 않았거나 아직 요청되지 않은 상태"),
	SENT("전송 완료", "Hermes를 통해 Slack 알림 전송을 완료한 상태"),
	FAILED("전송 실패", "Pipeline 결과는 보존했지만 Hermes 알림 전송은 실패한 상태");

	private final String titleKo;
	private final String descriptionKo;

	NewsCollectionNotificationStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}

	public boolean isFailed() {
		return this == FAILED;
	}
}
