package com.home.domain.news;

public enum MarketNewsQualityReviewStatus {
    READY("검토 준비", "필수 표본 수를 충족해 사람 검토를 시작할 수 있습니다."),
    INSUFFICIENT_SAMPLE("표본 부족", "하나 이상의 품질 지표가 최소 표본 수를 충족하지 못했습니다.");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsQualityReviewStatus(String titleKo, String descriptionKo) {
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
