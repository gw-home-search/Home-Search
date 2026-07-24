package com.home.domain.insight;

public enum MarketInsightPeriodType {
    DAILY("일간", "하나의 완결된 DAILY 실행 snapshot"),
    WEEKLY("달력 주간", "기존 월요일부터 일요일까지의 보존 snapshot"),
    ROLLING_7D("최근 7일", "최신 완결 DAILY 실행일을 포함한 최근 7일 snapshot"),
    MONTHLY("월간", "월 단위 snapshot");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightPeriodType(String titleKo, String descriptionKo) {
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
