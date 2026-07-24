package com.home.domain.complex.buildingregister;

public enum BuildingRatioResolutionMethod {
    RECAP_DIRECT("총괄 직접값", "총괄표제부가 제공한 양수 비율"),
    RECAP_COMPONENT_CALC("총괄 구성요소 계산", "총괄표제부 numerator와 대지면적으로 계산한 비율"),
    TITLE_DIRECT_CONSENSUS("표제부 직접값 합의", "모든 기대 표제부의 직접 비율이 투영 단위에서 일치"),
    RECAP_NUMERATOR_TITLE_DENOMINATOR("총괄·표제부 혼합 계산", "총괄 numerator와 표제부 합의 대지면적으로 계산한 비율"),
    TITLE_AGGREGATE_CALC("표제부 합산 계산", "완전한 기대 표제부 numerator 합계와 합의 대지면적으로 계산한 비율"),
    STANDALONE_TITLE_DIRECT("단독 표제부 직접값", "단독 표제부가 제공한 양수 비율"),
    STANDALONE_TITLE_COMPONENT_CALC("단독 표제부 구성요소 계산", "단독 표제부 numerator와 대지면적으로 계산한 비율");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRatioResolutionMethod(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean usesTitleHierarchyEvidence() {
        return switch (this) {
            case TITLE_DIRECT_CONSENSUS, RECAP_NUMERATOR_TITLE_DENOMINATOR, TITLE_AGGREGATE_CALC -> true;
            case RECAP_DIRECT, RECAP_COMPONENT_CALC, STANDALONE_TITLE_DIRECT, STANDALONE_TITLE_COMPONENT_CALC -> false;
        };
    }
}
