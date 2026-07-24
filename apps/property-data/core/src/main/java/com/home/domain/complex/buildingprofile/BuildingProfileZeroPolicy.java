package com.home.domain.complex.buildingprofile;

public enum BuildingProfileZeroPolicy {
    VALID("유효한 0", "0을 실제 측정값 또는 count로 보존"),
    MISSING_EQUIVALENT("누락 상당 0", "0을 보존하되 품질 coverage에서는 누락 상당값으로 분류"),
    INVALID("오류 0", "0을 보존하되 해당 필드의 유효값으로 사용하지 않음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileZeroPolicy(String titleKo, String descriptionKo) {
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
