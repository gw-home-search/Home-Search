package com.home.domain.complex.buildingprofile;

public enum BuildingProfileScope {
    SITE("대지", "대지 또는 root scope에서 한 번만 해석하는 값"),
    BUILDING("건물", "표제부 건물별로 해석하고 필요할 때 집계하는 값"),
    HIERARCHY("계층", "관리번호와 대장 관계를 설명하는 값");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileScope(String titleKo, String descriptionKo) {
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
