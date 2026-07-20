package com.home.domain.complex.buildingregister;

public enum BuildingRatioScope {
    UNIQUE_ROOT("단일 root", "단지와 건축물대장 root가 상호 유일하게 대응"),
    STANDALONE_TITLE("단독 표제부", "PNU의 단지와 표제부가 각각 하나인 단독 범위"),
    SHARED_RECAP("공유 총괄", "하나의 총괄표제부 범위를 여러 단지가 공유");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRatioScope(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean projectable() {
        return this != SHARED_RECAP;
    }
}
