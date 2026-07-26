package com.home.domain.complex.buildingprofile;

public enum BuildingProfileSourceMethod implements DescribedStoredValue {
    COMPLEX("단지 직접", "검증된 complex와 root 연결에서 얻은 값"),
    PNU_ROOT("PNU root", "같은 PNU의 현행 root 대장 값"),
    TITLE_AGGREGATE("표제부 집계", "complete title contributor 집합으로 계산한 값"),
    PROVIDER_DIRECT("provider 직접", "provider가 제공한 원천 비율 또는 총계 값");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileSourceMethod(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    @Override
    public String titleKo() {
        return titleKo;
    }

    @Override
    public String descriptionKo() {
        return descriptionKo;
    }
}
