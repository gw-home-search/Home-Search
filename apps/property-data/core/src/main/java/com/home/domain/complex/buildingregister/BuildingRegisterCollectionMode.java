package com.home.domain.complex.buildingregister;

public enum BuildingRegisterCollectionMode {
    MISSING("누락 수집", "캠페인 시작 시 비율이 하나라도 누락된 단지를 동결"),
    RETRY("실패 재시도", "동결된 캠페인의 미완료 target을 재처리");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterCollectionMode(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String storedValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }
}
