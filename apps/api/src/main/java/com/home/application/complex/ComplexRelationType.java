package com.home.application.complex;

/**
 * 같은 필지 또는 근접 단지 사이의 운영 관계 유형을 나타낸다. 이 값은 좌표 예외 처리의 분기 기준으로도 사용된다.
 */
public enum ComplexRelationType {

	SINGLE("단일", "필지 또는 기준 범위에 단지 하나만 존재해 추가 관계 검토가 필요 없는 상태"),
	CONCURRENT("동시 존재", "복수 단지가 같은 기간에 함께 거래되어 표시 좌표 분리가 필요한 상태"),
	REDEVELOPED("재건축/재개발", "거래 기간이 순차적으로 이어져 세대 교체 또는 정비사업으로 판단한 상태"),
	MASTER_UPDATE("마스터 갱신", "외부 마스터 데이터 변경으로 동일 단지 식별자가 갱신된 것으로 보는 상태"),
	UNRELATED("무관", "같은 필지 후보라도 거래 또는 식별 근거상 별도 관계로 판단한 상태"),
	UNKNOWN("미확정", "거래 기간, 사용승인일, 표본 수가 부족해 자동 판정할 수 없는 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexRelationType(String titleKo, String descriptionKo) {
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
	 * 단지별 표시 좌표 예외 해결 큐에 올려야 하는 관계인지 판단한다.
	 */
	public boolean requiresCoordinateExceptionResolution() {
		return this == CONCURRENT || this == UNKNOWN;
	}
}
