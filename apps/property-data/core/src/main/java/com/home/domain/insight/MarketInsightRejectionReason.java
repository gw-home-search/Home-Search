package com.home.domain.insight;

public enum MarketInsightRejectionReason {
    INELIGIBLE_COLLECTION_MODE("부적격 수집 모드", "DAILY 실행이 아님"),
    INELIGIBLE_SCOPE("부적격 수집 범위", "NATIONWIDE 실행이 아님"),
    NON_SUCCESSFUL_WORK_UNIT("실패 work unit", "PARTIAL 또는 FAILED 결과가 존재함"),
    INCOMPLETE_WORKSET("미완료 workset", "계획 수와 완료 수가 일치하지 않음");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightRejectionReason(String titleKo, String descriptionKo) {
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
