package com.home.domain.complex.buildingprofile;

public enum BuildingProfilePublicScope implements DescribedStoredValue {
    COMPLEX("단지 기준", "검증된 단일 complex에 귀속되는 값"),
    PARCEL("대지 기준", "같은 PNU의 현행 root consensus로 제공하는 값");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfilePublicScope(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    @Override public String titleKo() { return titleKo; }
    @Override public String descriptionKo() { return descriptionKo; }
}
