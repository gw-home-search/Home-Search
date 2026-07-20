package com.home.domain.complex.buildingregister;

public enum BuildingRegisterCollectionStrategy {
    ADAPTIVE("총괄 우선 적응형", "총괄표제부의 직접 비율이 누락된 필드만 계층 fallback으로 평가"),
    FULL_HIERARCHY("전체 계층 검증", "총괄표제부·표제부·기본개요를 함께 수집해 후보를 교차 검증");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterCollectionStrategy(String titleKo, String descriptionKo) {
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
