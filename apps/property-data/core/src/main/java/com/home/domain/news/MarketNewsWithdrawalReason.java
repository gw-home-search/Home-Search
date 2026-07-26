package com.home.domain.news;

public enum MarketNewsWithdrawalReason {
    RELEVANCE_PRECISION_BELOW_THRESHOLD("관련성 정밀도 미달", "부동산 의사결정 관련 기사 비율이 품질 기준에 미달함"),
    CATEGORY_ACCURACY_BELOW_THRESHOLD("카테고리 정확도 미달", "카테고리별 정확도가 품질 기준에 미달함"),
    RELATION_ACCURACY_BELOW_THRESHOLD("관계 정확도 미달", "지역 또는 단지 관계 정확도가 품질 기준에 미달함"),
    URL_SUCCESS_BELOW_THRESHOLD("원문 연결 성공률 미달", "반환 URL의 실제 원문 연결 성공률이 품질 기준에 미달함"),
    UNSAFE_PUBLIC_ITEM("공개 항목 안전성 실패", "markup, 위험 URL 또는 잘못된 제공 시각이 공개 항목에서 확인됨");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsWithdrawalReason(String titleKo, String descriptionKo) {
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
