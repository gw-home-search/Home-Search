package com.home.domain.insight;

public enum MarketInsightScopeType {
    NATIONWIDE("전국", "전국 범위 snapshot"),
    SIDO("시도", "하나의 시도 범위 snapshot");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightScopeType(String titleKo, String descriptionKo) {
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
