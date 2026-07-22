package com.home.domain.complex.buildingprofile;

public enum BuildingProfileAggregation {
    DIRECT("직접값", "원천 record의 값을 직접 비교"),
    SUM("합계", "완전한 기대 contributor 집합만 합산"),
    MAX("최댓값", "건물별 값의 최댓값과 분포를 함께 보존"),
    CONSENSUS("합의값", "정규화된 값이 모두 같을 때만 단일값 생성"),
    SET("집합", "code와 name의 distinct 집합 및 빈도를 보존"),
    RECALCULATED("재계산", "공식 구성요소와 DECIMAL128로 계산");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileAggregation(String titleKo, String descriptionKo) {
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
