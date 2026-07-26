package com.home.domain.complex.buildingprofile;

public enum BuildingProfilePublicQuality implements DescribedStoredValue {
    VERIFIED("검증됨", "complete contributor 또는 검증된 complex 값"),
    PNU_FALLBACK("PNU fallback", "충돌 없는 대지 root consensus 값"),
    PARTIAL("일부 확인", "확인된 contributor만으로 제공하며 direct 보강에는 사용하지 않는 값");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfilePublicQuality(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    @Override public String titleKo() { return titleKo; }
    @Override public String descriptionKo() { return descriptionKo; }
}
