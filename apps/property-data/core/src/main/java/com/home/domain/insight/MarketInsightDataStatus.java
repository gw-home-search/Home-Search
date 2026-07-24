package com.home.domain.insight;

public enum MarketInsightDataStatus {
    FRESH("최신", "요청 날짜에 발행된 거래 인사이트"),
    STALE("이전 집계", "요청 날짜보다 이전에 발행된 최신 거래 인사이트"),
    UNAVAILABLE("준비 중", "공개할 수 있는 거래 인사이트가 아직 없음");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightDataStatus(String titleKo, String descriptionKo) {
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
