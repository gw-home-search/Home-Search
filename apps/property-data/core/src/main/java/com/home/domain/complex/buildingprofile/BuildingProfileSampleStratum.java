package com.home.domain.complex.buildingprofile;

public enum BuildingProfileSampleStratum {
    SHARED_PNU("공유 PNU", "둘 이상의 complex가 연결된 PNU 전수"),
    LEGAL_CODE_TRANSITION("법정동코드 전환", "공식 mapping의 구 법정동코드 영향을 받는 PNU"),
    HIERARCHY_RISK("계층 위험", "기존 evidence에서 계층·신구대장 충돌 위험이 관찰된 PNU"),
    HIGH_COMPLEXITY("고복잡도", "title 수 상위 10% 모집단의 seed 표본"),
    METADATA_CONTROL("metadata control", "기존 metadata가 충분한 대조 표본"),
    REGIONAL_PROPORTIONAL("지역 비례", "남은 시도별 모집단 비례 seed 표본"),
    NATIONWIDE_CENSUS("전국 전수", "유효한 PNU가 연결된 전체 complex 모집단 전수");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileSampleStratum(String titleKo, String descriptionKo) {
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
