package com.home.domain.insight;

public enum MarketInsightMetricType {
    DAILY_NEW_TRADE("오늘 새 공개 거래", "자격 있는 DAILY 실행에서 최초 정규화된 거래"),
    DAILY_HIGHEST_DEAL("오늘 최고 거래", "오늘 새 공개 거래 중 최고 거래금액"),
    WEEKLY_HIGHEST_DEAL("주간 최고 거래", "직전 월요일부터 일요일까지 공개된 최고 거래"),
    AREA_RECORD_HIGH("면적형 신고가", "같은 단지와 exact 면적형의 과거 최고가를 엄격히 초과"),
    AREA_PREVIOUS_RISE("직전 거래 상승", "직전 distinct 계약일 median보다 상승"),
    AREA_PREVIOUS_FALL("직전 거래 하락", "직전 distinct 계약일 median보다 하락"),
    WEEKLY_DISCLOSURE_ACTIVITY("주간 공개 활동", "직전 주 단지별 신규 공개 건수"),
    CANCELLATION_CORRECTION("취소 정정", "DAILY 실행에서 최초 active에서 canceled로 전이"),
    TRADE_ACTIVITY_30D("30일 활동량", "최근 30일 잠정 관측 활성 거래 수"),
    AREA_MOMENTUM_30D("30일 면적형 모멘텀", "publication buffer 뒤 두 30일 구간 비교"),
    AREA_PRICE_RISE_90D("90일 면적형 상승", "publication buffer 뒤 두 90일 구간 비교"),
    AREA_PRICE_FALL_90D("90일 면적형 하락", "publication buffer 뒤 두 90일 구간 비교"),
    TRADE_VOLUME_RISE_90D("90일 거래량 상승", "단지 거래량 두 90일 구간 비교"),
    TRADE_VOLUME_FALL_90D("90일 거래량 하락", "단지 거래량 두 90일 구간 비교"),
    REGION_OBSERVED_CHANGE_MONTHLY("월간 지역 관측 변화", "공표 가능한 월의 시도 median 변화");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightMetricType(String titleKo, String descriptionKo) {
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
