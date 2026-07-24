package com.home.domain.complex.buildingregister;

public enum BuildingRatioResolutionStatus {
    SELECTED("선택", "일치하는 유효 후보 중 provenance 우선순위로 선택"),
    SOURCE_MISSING("원천 누락", "완전성 조건을 통과한 후보가 없음"),
    SOURCE_CONFLICT("원천 충돌", "후보의 투영값 차이가 0.01 percentage point를 초과"),
    SKIPPED_SHARED_SCOPE("공유 범위 보류", "하나의 총괄 범위를 여러 단지가 공유해 투영하지 않음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRatioResolutionStatus(String titleKo, String descriptionKo) {
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
