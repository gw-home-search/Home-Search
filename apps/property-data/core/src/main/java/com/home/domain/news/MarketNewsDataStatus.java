package com.home.domain.news;

public enum MarketNewsDataStatus {
    FRESH("최신", "정상 발행 후 여덟 시간 이내인 뉴스"),
    STALE("이전 뉴스", "마지막 정상 발행을 유지하는 뉴스"),
    UNAVAILABLE("준비 중", "공개할 정상 뉴스 발행이 아직 없음");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsDataStatus(String titleKo, String descriptionKo) {
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
