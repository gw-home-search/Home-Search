package com.home.domain.news;

import java.time.OffsetDateTime;

/**
 * 뉴스 수집 keyword의 재실행 주기를 저장하고 다음 실행 시각을 계산하는 정책입니다.
 */
public enum NewsCollectionKeywordCadence {

	DAILY("매일", "매일 한 번 수집 대상이 되는 keyword"),
	WEEKLY("매주", "주 1회 회전 수집 대상이 되는 keyword"),
	MANUAL("수동", "자동 재실행 없이 운영자가 next_due_at을 다시 지정해야 하는 keyword");

	private final String titleKo;
	private final String descriptionKo;

	NewsCollectionKeywordCadence(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}

	public OffsetDateTime nextDueAfter(OffsetDateTime collectedAt) {
		return switch (this) {
			case DAILY -> collectedAt.plusDays(1);
			case WEEKLY -> collectedAt.plusWeeks(1);
			case MANUAL -> collectedAt.plusYears(100);
		};
	}
}
