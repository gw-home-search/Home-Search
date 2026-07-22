package com.home.domain.insight;

public enum RtmsCollectionScopeType {
    NATIONWIDE("전국", "계획된 전국 법정동 범위를 포함"),
    TARGETED("지정 범위", "운영자가 지정한 일부 지역 범위");

    private final String titleKo;
    private final String descriptionKo;

    RtmsCollectionScopeType(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean qualifiesForNationwideInsight() {
        return this == NATIONWIDE;
    }
}
