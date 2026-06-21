package com.home.domain.news;

public enum NewsRegionBucket {

	NATIONAL("전국", "전국 단위 뉴스 bucket입니다."),
	SEOUL("서울", "서울 전체 뉴스 bucket입니다."),
	GYEONGGI("경기", "경기 전체 뉴스 bucket입니다."),
	OTHER("기타", "서울/경기/전국 주요 bucket 밖의 뉴스 bucket입니다."),
	SEOUL_GANGNAM_GU("서울 강남구", "서울 강남구 detail bucket입니다."),
	SEOUL_SEOCHO_GU("서울 서초구", "서울 서초구 detail bucket입니다."),
	SEOUL_SONGPA_GU("서울 송파구", "서울 송파구 detail bucket입니다."),
	SEOUL_YONGSAN_GU("서울 용산구", "서울 용산구 detail bucket입니다."),
	SEOUL_MAPO_GU("서울 마포구", "서울 마포구 detail bucket입니다."),
	SEOUL_SEONGDONG_GU("서울 성동구", "서울 성동구 detail bucket입니다."),
	SEOUL_YEONGDEUNGPO_GU("서울 영등포구", "서울 영등포구 detail bucket입니다."),
	SEOUL_YANGCHEON_GU("서울 양천구", "서울 양천구 detail bucket입니다."),
	SEOUL_NOWON_GU("서울 노원구", "서울 노원구 detail bucket입니다."),
	SEOUL_GANGDONG_GU("서울 강동구", "서울 강동구 detail bucket입니다."),
	GYEONGGI_SEONGNAM_SI("경기 성남시", "경기 성남시 detail bucket입니다."),
	GYEONGGI_GWACHEON_SI("경기 과천시", "경기 과천시 detail bucket입니다."),
	GYEONGGI_HANAM_SI("경기 하남시", "경기 하남시 detail bucket입니다."),
	GYEONGGI_GWANGMYEONG_SI("경기 광명시", "경기 광명시 detail bucket입니다."),
	GYEONGGI_GOYANG_SI("경기 고양시", "경기 고양시 detail bucket입니다."),
	GYEONGGI_YONGIN_SI("경기 용인시", "경기 용인시 detail bucket입니다."),
	GYEONGGI_SUWON_SI("경기 수원시", "경기 수원시 detail bucket입니다."),
	GYEONGGI_HWASEONG_SI("경기 화성시", "경기 화성시 detail bucket입니다."),
	GYEONGGI_NAMYANGJU_SI("경기 남양주시", "경기 남양주시 detail bucket입니다."),
	GYEONGGI_GIMPO_SI("경기 김포시", "경기 김포시 detail bucket입니다."),
	GYEONGGI_ANYANG_SI("경기 안양시", "경기 안양시 detail bucket입니다."),
	GYEONGGI_UIWANG_SI("경기 의왕시", "경기 의왕시 detail bucket입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsRegionBucket(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isPilotBucket() {
		return this == NATIONAL
			|| this == SEOUL_GANGNAM_GU
			|| this == SEOUL_SONGPA_GU
			|| this == GYEONGGI_SEONGNAM_SI
			|| this == GYEONGGI_GWACHEON_SI;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
