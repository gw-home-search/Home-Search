package com.home.domain.news;

public enum MarketNewsCategory {
    ALL("전체", "모든 부동산 뉴스 카테고리"),
    POLICY("정책", "주택과 부동산 제도 및 정책"),
    FINANCE_LOAN("금융·대출", "주택 금융과 담보대출"),
    SUPPLY_SALE("공급·분양", "주택 공급과 분양"),
    REDEVELOPMENT("재건축·재개발", "재건축과 재개발 정비사업"),
    TRANSACTION_PRICE("거래·가격", "아파트 매매 거래와 가격"),
    TRANSPORT_DEVELOPMENT("교통·지역개발", "교통망과 지역 개발");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsCategory(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean isStoredCategory() {
        return this != ALL;
    }
}
