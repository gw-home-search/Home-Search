package com.home.domain.complex.buildingprofile;

public enum BuildingProfileConflictStatus implements DescribedStoredValue {
    NONE("충돌 없음", "effective 값으로 사용할 수 있는 evidence"),
    SOURCE_CONFLICT("원천 충돌", "동일 scope의 원천 값이 일치하지 않음"),
    AGGREGATE_CONFLICT("집계 충돌", "provider 직접 값과 계산 값이 허용차를 벗어남"),
    INCOMPLETE("불완전", "기대 contributor 일부가 없어 complete 집계를 만들 수 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileConflictStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    @Override public String titleKo() { return titleKo; }
    @Override public String descriptionKo() { return descriptionKo; }
}
