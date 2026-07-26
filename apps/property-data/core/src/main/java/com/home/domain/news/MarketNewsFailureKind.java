package com.home.domain.news;

public enum MarketNewsFailureKind {
    AUTHENTICATION("인증 실패", "뉴스 provider 인증이 거부됨"),
    DAILY_QUOTA("Provider 일일 한도", "뉴스 provider의 일일 호출 한도가 소진됨"),
    TRANSIENT("일시 실패", "재시도 가능한 provider 또는 network 오류가 발생함"),
    INVALID_RESPONSE("응답 형식 오류", "provider 응답을 안전하게 해석할 수 없음"),
    DAILY_CALL_BUDGET("내부 호출 예산", "서비스가 정한 일일 뉴스 호출 예산이 소진됨"),
    RAW_POSITION_CONFLICT("원문 위치 충돌", "동일 provider 위치의 payload가 저장된 raw evidence와 다름"),
    CUTOFF_NOT_REACHED("수집 범위 잘림", "provider start 한도 전에 overlap cutoff에 도달하지 못함"),
    INTERNAL("내부 처리 실패", "뉴스 정규화 또는 영속 처리 중 내부 오류가 발생함"),
    CACHE_PUBLICATION("Cache 발행 실패", "완료된 뉴스 snapshot을 publication cache에 반영하지 못함");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsFailureKind(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean stopsRemainingWork() {
        return this == AUTHENTICATION || this == DAILY_QUOTA || this == DAILY_CALL_BUDGET;
    }
}
