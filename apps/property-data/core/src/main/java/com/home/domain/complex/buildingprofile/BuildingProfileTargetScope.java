package com.home.domain.complex.buildingprofile;

public enum BuildingProfileTargetScope {
    VALIDATION_SAMPLE("검증 표본", "고정 seed와 strata로 선택한 1,500 PNU 검증 표본"),
    NATIONWIDE_STAGING("전국 staging", "유효한 PNU가 연결된 전체 complex 모집단의 재개 가능한 staging 수집");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileTargetScope(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean isValidationSample() {
        return this == VALIDATION_SAMPLE;
    }
}
