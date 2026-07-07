package com.home.domain.ingest.raw;

/**
 * raw ingest row에 저장하는 durable failure reason 값을 관리한다. 저장 문자열은 운영 증거와 테스트 계약이므로 값 변경 없이 유지한다.
 */
public enum RawTradeIngestFailureReason {

	SOURCE_KEY_DUPLICATE(
		"source_key 중복",
		"이미 처리된 source/source_key 원천 row라 normalized trade를 추가하지 않은 상태",
		"duplicate source/source_key"
	),
	FALLBACK_IDENTITY_DUPLICATE(
		"fallback identity 중복",
		"source_key가 다르지만 fallback 거래 identity가 중복되어 normalized trade를 추가하지 않은 상태",
		"duplicate fallback identity"
	),
	CANCELED_SOURCE_KEY(
		"source_key 취소",
		"원천 취소 row가 source/source_key registry를 예약하거나 기존 normalized trade를 취소한 상태",
		"canceled source/source_key"
	),
	REMATCH_SOURCE_KEY_DUPLICATE(
		"rematch source_key 중복",
		"failed match rematch 중 이미 처리된 source/source_key가 확인된 상태",
		"rematch duplicate source/source_key"
	),
	REMATCH_FALLBACK_IDENTITY_DUPLICATE(
		"rematch fallback identity 중복",
		"failed match rematch 중 fallback 거래 identity가 중복된 상태",
		"rematch duplicate fallback identity"
	);

	private final String titleKo;
	private final String descriptionKo;
	private final String value;

	RawTradeIngestFailureReason(String titleKo, String descriptionKo, String value) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
		this.value = value;
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
	 * raw ingest failure_reason에 저장할 안정적인 문자열 값을 반환한다.
	 */
	public String value() {
		return value;
	}
}
