package com.home.news.infrastructure.external.openai;

import java.util.List;
import java.util.Map;

import com.home.domain.news.NewsRegionBucket;

record RegionSearchProfile(
	NewsRegionBucket bucket,
	String nameKo,
	List<String> aliases
) {

	private static final Map<NewsRegionBucket, RegionSearchProfile> PROFILES = Map.ofEntries(
		Map.entry(NewsRegionBucket.NATIONAL, new RegionSearchProfile(NewsRegionBucket.NATIONAL, "전국", List.of("전국", "부동산", "아파트", "주택시장"))),
		Map.entry(NewsRegionBucket.SEOUL, new RegionSearchProfile(NewsRegionBucket.SEOUL, "서울", List.of("서울", "강남권", "한강변", "서울 아파트"))),
		Map.entry(NewsRegionBucket.GYEONGGI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI, "경기", List.of("경기", "수도권", "경기도", "경기 아파트"))),
		Map.entry(NewsRegionBucket.OTHER, new RegionSearchProfile(NewsRegionBucket.OTHER, "기타", List.of("지방", "광역시", "부동산"))),
		Map.entry(NewsRegionBucket.SEOUL_GANGNAM_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_GANGNAM_GU, "강남구", List.of("강남구", "강남", "대치", "개포", "압구정", "삼성", "역삼", "도곡", "수서"))),
		Map.entry(NewsRegionBucket.SEOUL_SEOCHO_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_SEOCHO_GU, "서초구", List.of("서초구", "서초", "반포", "잠원", "방배", "양재"))),
		Map.entry(NewsRegionBucket.SEOUL_SONGPA_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_SONGPA_GU, "송파구", List.of("송파구", "송파", "잠실", "가락", "문정", "위례", "마천", "거여"))),
		Map.entry(NewsRegionBucket.SEOUL_YONGSAN_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_YONGSAN_GU, "용산구", List.of("용산구", "용산", "한남", "이촌", "서빙고", "원효로"))),
		Map.entry(NewsRegionBucket.SEOUL_MAPO_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_MAPO_GU, "마포구", List.of("마포구", "마포", "공덕", "아현", "상암", "합정"))),
		Map.entry(NewsRegionBucket.SEOUL_SEONGDONG_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_SEONGDONG_GU, "성동구", List.of("성동구", "성수", "왕십리", "옥수", "금호", "행당"))),
		Map.entry(NewsRegionBucket.SEOUL_YEONGDEUNGPO_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_YEONGDEUNGPO_GU, "영등포구", List.of("영등포구", "여의도", "문래", "신길", "당산"))),
		Map.entry(NewsRegionBucket.SEOUL_YANGCHEON_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_YANGCHEON_GU, "양천구", List.of("양천구", "목동", "신정", "신월"))),
		Map.entry(NewsRegionBucket.SEOUL_NOWON_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_NOWON_GU, "노원구", List.of("노원구", "상계", "중계", "하계", "월계"))),
		Map.entry(NewsRegionBucket.SEOUL_GANGDONG_GU, new RegionSearchProfile(NewsRegionBucket.SEOUL_GANGDONG_GU, "강동구", List.of("강동구", "둔촌", "고덕", "명일", "상일", "암사"))),
		Map.entry(NewsRegionBucket.GYEONGGI_SEONGNAM_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_SEONGNAM_SI, "성남시", List.of("성남", "분당", "판교", "수정구", "중원구", "위례"))),
		Map.entry(NewsRegionBucket.GYEONGGI_GWACHEON_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_GWACHEON_SI, "과천시", List.of("과천", "과천지식정보타운", "정부과천청사", "별양", "부림"))),
		Map.entry(NewsRegionBucket.GYEONGGI_HANAM_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_HANAM_SI, "하남시", List.of("하남", "미사", "감일", "위례", "교산"))),
		Map.entry(NewsRegionBucket.GYEONGGI_GWANGMYEONG_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_GWANGMYEONG_SI, "광명시", List.of("광명", "철산", "하안", "소하", "광명뉴타운"))),
		Map.entry(NewsRegionBucket.GYEONGGI_GOYANG_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_GOYANG_SI, "고양시", List.of("고양", "일산", "덕양", "킨텍스", "대곡"))),
		Map.entry(NewsRegionBucket.GYEONGGI_YONGIN_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_YONGIN_SI, "용인시", List.of("용인", "수지", "기흥", "처인", "동백"))),
		Map.entry(NewsRegionBucket.GYEONGGI_SUWON_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_SUWON_SI, "수원시", List.of("수원", "광교", "영통", "권선", "장안", "팔달"))),
		Map.entry(NewsRegionBucket.GYEONGGI_HWASEONG_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_HWASEONG_SI, "화성시", List.of("화성", "동탄", "봉담", "병점", "향남"))),
		Map.entry(NewsRegionBucket.GYEONGGI_NAMYANGJU_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_NAMYANGJU_SI, "남양주시", List.of("남양주", "다산", "별내", "왕숙", "평내"))),
		Map.entry(NewsRegionBucket.GYEONGGI_GIMPO_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_GIMPO_SI, "김포시", List.of("김포", "한강신도시", "장기", "걸포", "풍무"))),
		Map.entry(NewsRegionBucket.GYEONGGI_ANYANG_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_ANYANG_SI, "안양시", List.of("안양", "평촌", "동안구", "만안구", "인덕원"))),
		Map.entry(NewsRegionBucket.GYEONGGI_UIWANG_SI, new RegionSearchProfile(NewsRegionBucket.GYEONGGI_UIWANG_SI, "의왕시", List.of("의왕", "인덕원", "백운", "오전", "내손")))
	);

	static RegionSearchProfile forBucket(NewsRegionBucket bucket) {
		return PROFILES.getOrDefault(bucket, new RegionSearchProfile(bucket, bucket.titleKo(), List.of(bucket.titleKo())));
	}

	String aliasesText() {
		return String.join(", ", aliases);
	}
}
