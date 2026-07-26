package com.home.domain.complex.buildingprofile;

public enum BuildingProfileSeismicDesignStatus implements DescribedStoredValue {
    ALL_APPLIED("전체 적용", "확인된 모든 건물이 내진설계 적용"),
    PARTIAL("일부 적용", "내진설계 적용과 미적용 건물이 함께 존재"),
    NONE_APPLIED("미적용", "확인된 모든 건물이 내진설계 미적용"),
    UNKNOWN("확인 불가", "유효한 내진설계 적용 정보가 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileSeismicDesignStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    @Override public String titleKo() { return titleKo; }
    @Override public String descriptionKo() { return descriptionKo; }
}
