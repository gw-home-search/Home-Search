package com.home.domain.news;

public enum MarketNewsQualitySampleStratum {
    COMPLEX_CHALLENGE("동명·짧은 단지", "동명 또는 정규화 이름이 4자 이하인 단지 관계 표본입니다."),
    DIRECT_COMPLEX("단지 직접 언급", "단지 직접 언급 관계의 정확도를 검토하는 표본입니다."),
    SAME_DONG("같은 동", "법정동 관계의 정확도를 검토하는 표본입니다."),
    SAME_SIGUNGU("같은 시군구", "시군구 관계의 정확도를 검토하는 표본입니다."),
    SIDO_COVERAGE("시도", "17개 시도별 최소 표본 수를 확인하는 표본입니다."),
    CATEGORY("카테고리", "카테고리별 정확도를 확인하는 표본입니다."),
    URL_OPEN("원문 URL", "provider가 반환한 원문 URL 연결을 확인하는 표본입니다.");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsQualitySampleStratum(String titleKo, String descriptionKo) {
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
