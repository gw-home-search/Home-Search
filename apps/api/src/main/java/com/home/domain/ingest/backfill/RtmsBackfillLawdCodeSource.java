package com.home.domain.ingest.backfill;

/**
 * RTMS nationwide backfill chunk를 생성한 법정동 코드 목록의 출처를 나타낸다.
 */
public enum RtmsBackfillLawdCodeSource {

	REGION_SI_GUN_GU("region.si-gun-gu", "시군구 region", "region 계층의 시군구 법정동 코드로 chunk를 생성한 출처");

	private final String storedValue;
	private final String titleKo;
	private final String descriptionKo;

	RtmsBackfillLawdCodeSource(String storedValue, String titleKo, String descriptionKo) {
		this.storedValue = storedValue;
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String storedValue() {
		return storedValue;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
