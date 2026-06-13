package com.home.domain.complex.metadata;

/**
 * 단지 메타데이터 원천 후보를 찾은 조회 경로를 나타내며 enrichment attempt 증거로 저장된다.
 */
public enum ComplexMetadataLookupPath {

	CANONICAL_PNU("현재 PNU", "운영 parcel PNU를 그대로 사용한 정확 조회"),
	CANONICAL_PNU_NAME("현재 PNU + 단지명", "정확 PNU 후보가 여럿일 때 단지명 일치로 유일 단지를 선택한 조회"),
	APPROVED_PREFIX_ALIAS("승인 PNU 별칭", "승인된 ODC 전용 구 PNU prefix를 사용한 정확 조회"),
	APPROVED_PREFIX_ALIAS_NAME("승인 PNU 별칭 + 단지명", "승인된 구 PNU prefix 후보가 여럿일 때 단지명 일치로 유일 단지를 선택한 조회"),
	COMPLEX_PK_DIAGNOSTIC("단지 식별자 진단", "COMPLEX_PK를 충돌 검증에 사용한 조회"),
	BUILDING_PNU("건축물 PNU", "건축물대장 API에 운영 PNU를 사용한 조회"),
	NONE("조회 없음", "외부 원천 조회를 수행하지 못한 상태");

	private final String titleKo;
	private final String descriptionKo;

	ComplexMetadataLookupPath(String titleKo, String descriptionKo) {
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
