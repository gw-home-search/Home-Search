package com.home.domain.news;

public enum SignalImpactTarget {

	sale_price("매매가", "매매 가격 방향 신호입니다."),
	jeonse_price("전세가", "전세 가격 방향 신호입니다."),
	volume("거래량", "거래량 방향 신호입니다."),
	supply("공급", "공급 물량 방향 신호입니다."),
	liquidity("유동성", "시장 유동성 방향 신호입니다."),
	risk("위험", "시장 위험 신호입니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalImpactTarget(String titleKo, String descriptionKo) {
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
