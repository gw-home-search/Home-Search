package com.home.domain.complex.buildingprofile;

public enum BuildingProfileComparisonStatus {
    MATCH("일치", "정확 비교에서 동일"),
    WITHIN_TOLERANCE("허용차 이내", "면적 또는 비율 허용차 이내"),
    CONFLICT("충돌", "비교 가능한 원천 값이 허용차 밖에서 다름"),
    INCOMPLETE("불완전", "기대 contributor가 누락되어 SUM을 만들지 않음"),
    NOT_COMPARABLE("비교 불가", "provider 실패 또는 의미가 다른 값");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileComparisonStatus(String titleKo, String descriptionKo) {
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
