package com.home.domain.complex.buildingregister;

public enum BuildingRegisterMatchPath {
    EXISTING_KEY("기존 관리번호", "기존 관리번호가 root 또는 단독 표제부와 정확히 일치"),
    UNIQUE_PNU("PNU 상호 유일", "PNU 안의 단지와 source root가 각각 하나"),
    EXACT_NAME("이름 정확 일치", "정규화된 단지 alias와 건물명이 상호 유일하게 일치"),
    EXACT_DONG_SET("동명 집합 정확 일치", "거래 동명과 표제부 동명 집합이 상호 유일하게 일치"),
    FOOTPRINT_EVIDENCE("footprint 동명", "신뢰 가능한 footprint 동명 집합이 정확히 일치");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterMatchPath(String titleKo, String descriptionKo) {
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
