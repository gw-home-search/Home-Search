package com.home.domain.complex.buildingprofile;

public enum BuildingProfileValueType {
    TEXT("문자열", "원천 문자열을 공백 정규화 후 보존"),
    DECIMAL("소수", "BigDecimal로 손실 없이 보존"),
    INTEGER("정수", "소수부 없는 정수로 보존"),
    DATE("날짜", "yyyyMMdd 계열 날짜를 LocalDate로 보존"),
    BOOLEAN("논리", "provider 적용 여부 코드를 논리값으로 보존");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileValueType(String titleKo, String descriptionKo) {
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
