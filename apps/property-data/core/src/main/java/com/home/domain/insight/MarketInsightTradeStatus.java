package com.home.domain.insight;

public enum MarketInsightTradeStatus {
    ACTIVE("유효 거래", "집계 기준 시점에 취소되지 않은 거래"),
    CANCELED("취소", "집계 또는 조회 시점에 취소가 확인된 거래");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightTradeStatus(String titleKo, String descriptionKo) {
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
