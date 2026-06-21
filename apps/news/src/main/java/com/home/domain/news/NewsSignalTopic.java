package com.home.domain.news;

public enum NewsSignalTopic {

	policy_regulation("정책/규제", "부동산 정책과 규제 뉴스입니다."),
	tax("세금", "부동산 세제 뉴스입니다."),
	loan_rate("대출/금리", "대출 조건과 금리 뉴스입니다."),
	subscription("청약", "청약 제도와 분양 신청 뉴스입니다."),
	reconstruction_redevelopment("재건축/재개발", "정비사업 관련 뉴스입니다."),
	supply("공급", "주택 공급과 입주 물량 뉴스입니다."),
	transport_infra("교통 인프라", "교통망과 기반시설 뉴스입니다."),
	school_district("학군", "교육 환경과 학군 뉴스입니다."),
	jeonse_rent("전월세", "전세와 월세 시장 뉴스입니다."),
	transaction_volume("거래량", "거래량과 시장 유동성 뉴스입니다."),
	auction_distress("경매/부실", "경매와 부실 위험 뉴스입니다."),
	unsold_inventory("미분양", "미분양 재고 뉴스입니다."),
	development_project("개발사업", "지역 개발사업 뉴스입니다."),
	macro_rate("거시금리", "거시 경제와 기준금리 뉴스입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsSignalTopic(String titleKo, String descriptionKo) {
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
