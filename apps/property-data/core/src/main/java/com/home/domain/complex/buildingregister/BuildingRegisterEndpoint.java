package com.home.domain.complex.buildingregister;

public enum BuildingRegisterEndpoint {
    RECAP_TITLE("총괄표제부", "대지 전체의 건축물대장 요약"),
    TITLE("표제부", "일반건축물 또는 동 단위 건축물대장"),
    BASIC_OVERVIEW("기본개요", "관리번호 상하위 관계를 확인하는 건축물대장 기본개요");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterEndpoint(String titleKo, String descriptionKo) {
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
