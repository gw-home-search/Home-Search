package com.home.domain.news;

public enum NewsRejectionReason {
    MISSING_REQUIRED_FIELD("필수 필드 누락", "제목, URL 또는 제공 시각이 없음"),
    INVALID_URL("위험 URL", "HTTP 또는 HTTPS 공개 URL 검증을 통과하지 못함"),
    INVALID_PROVIDED_AT("제공 시각 오류", "provider 제공 시각을 안전하게 해석할 수 없음"),
    OUTSIDE_RETENTION_WINDOW("보관 기간 밖", "최근 30일 공개 범위를 벗어남"),
    NOT_REAL_ESTATE_RELEVANT("부동산 관련성 부족", "allowlist 부동산 근거가 없음"),
    REGION_AMBIGUOUS("지역 모호", "필요한 상위 지역 조합을 확인하지 못함"),
    COMPLEX_AMBIGUOUS("단지 모호", "동명 또는 짧은 단지의 지역 근거가 부족함"),
    DUPLICATE_ARTICLE("중복 기사", "같은 canonical URL article이 이미 존재함");

    private final String titleKo;
    private final String descriptionKo;

    NewsRejectionReason(String titleKo, String descriptionKo) {
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
