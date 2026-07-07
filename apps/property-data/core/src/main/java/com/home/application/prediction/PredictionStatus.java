package com.home.application.prediction;

public enum PredictionStatus {

	READY(
		"예측 완료",
		"Redis cache에 사용 가능한 예측 결과가 저장된 상태입니다."
	),
	PENDING(
		"예측 계산 중",
		"예측 cache miss 이후 async 계산이 시작되었거나 이미 진행 중인 상태입니다."
	),
	FAILED(
		"예측 실패",
		"외부 ML service 호출, timeout, 응답 파싱, cache 읽기 중 실패가 격리된 상태입니다."
	),
	UNAVAILABLE(
		"예측 불가",
		"최근 거래, 면적, PNU 등 예측 feature를 만들 수 없어 계산하지 않는 상태입니다."
	);

	private final String titleKo;
	private final String descriptionKo;

	PredictionStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}

	public boolean terminal() {
		return this == READY || this == FAILED || this == UNAVAILABLE;
	}
}
