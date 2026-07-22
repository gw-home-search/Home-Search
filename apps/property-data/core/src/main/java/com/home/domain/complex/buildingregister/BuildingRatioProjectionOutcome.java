package com.home.domain.complex.buildingregister;

public enum BuildingRatioProjectionOutcome {
    APPLIED("적용", "NULL 비율 필드에 선택 candidate 값을 적용"),
    ALREADY_EQUAL("기존값 동일", "기존 non-null 값이 선택 candidate와 동일"),
    SKIPPED_EXISTING_CONFLICT("기존값 충돌", "기존 non-null 값이 선택 candidate와 달라 보존"),
    SKIPPED_SHARED_SCOPE("공유 범위 보류", "여러 단지가 공유하는 총괄 범위라 투영하지 않음"),
    SKIPPED_SOURCE_CONFLICT("원천 충돌 보류", "candidate 원천 간 차이로 투영하지 않음"),
    SOURCE_MISSING("원천 누락", "적용 가능한 candidate가 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRatioProjectionOutcome(String titleKo, String descriptionKo) {
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
