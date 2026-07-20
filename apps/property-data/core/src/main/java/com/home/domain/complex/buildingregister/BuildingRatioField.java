package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;

public enum BuildingRatioField {
    BUILDING_COVERAGE_RATIO("건폐율", "건축면적 합계를 단일 대지면적으로 나눈 비율"),
    FLOOR_AREA_RATIO("용적률", "용적률 산정용 연면적 합계를 단일 대지면적으로 나눈 비율");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRatioField(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    BigDecimal directRatio(BuildingRegisterRecord record) {
        return this == BUILDING_COVERAGE_RATIO ? record.bcRat() : record.vlRat();
    }

    BigDecimal numerator(BuildingRegisterRecord record) {
        return this == BUILDING_COVERAGE_RATIO ? record.archArea() : record.vlRatEstmTotArea();
    }
}
