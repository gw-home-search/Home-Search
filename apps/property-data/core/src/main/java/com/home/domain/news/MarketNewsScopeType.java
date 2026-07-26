package com.home.domain.news;

public enum MarketNewsScopeType {
    NATIONWIDE("전국", "전국 공통 뉴스 범위"),
    SIDO("시도", "한 개 시도 뉴스 범위");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsScopeType(String titleKo, String descriptionKo) {
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
