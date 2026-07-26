package com.home.domain.complex.buildingprofile;

public enum BuildingProfileProjectionUse {
    OPERATIONAL("운영 후보", "운영 조회에 사용할 수 있는 품질 후보"),
    PROFILE_ONLY("프로필 보존", "유효값을 보존하지만 운영 대표값으로 승격하지 않는 필드"),
    OBSERVATION_ONLY("관찰 전용", "원천값은 보존하지만 운영 계산과 표시에서 제외하는 필드");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileProjectionUse(String titleKo, String descriptionKo) {
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
