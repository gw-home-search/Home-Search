package com.home.domain.news;

public enum MarketNewsWorkUnitKind {
    NATIONAL_CATEGORY("전국 카테고리", "전국 고정 카테고리 검색"),
    SIDO("시도", "시도별 지역 검색"),
    MAJOR_COMPLEX("주요 단지", "주요 단지 exact 검색");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsWorkUnitKind(String titleKo, String descriptionKo) {
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
