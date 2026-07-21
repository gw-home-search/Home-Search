package com.home.domain.complex.buildingprofile;

public enum BuildingProfileQualityTier {
    PROMOTE_CANDIDATE("운영 승격 후보", "coverage·invalid·conflict·의미 검증 기준을 모두 충족", true),
    RETAIN_PROFILE("profile 보존", "유효 typed 값은 있으나 승격 기준 미달", true),
    RAW_ONLY("raw 전용", "의미 또는 type이 아직 안정되지 않음", false),
    REJECT_FOR_PROJECTION("projection 거절", "불완전하거나 모순되어 운영 projection에 부적합", true);

    private final String titleKo;
    private final String descriptionKo;
    private final boolean retainsTypedValue;

    BuildingProfileQualityTier(String titleKo, String descriptionKo, boolean retainsTypedValue) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
        this.retainsTypedValue = retainsTypedValue;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean retainsTypedValue() {
        return retainsTypedValue;
    }
}
