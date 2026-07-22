package com.home.domain.complex.buildingprofile;

public enum BuildingProfileParseStatus {
    PARSED("파싱 완료", "typed profile record를 생성"),
    EMPTY("정상 빈 응답", "provider 성공이지만 record가 없음"),
    PROVIDER_FAILED("provider 실패", "HTTP 또는 provider 실패로 분석 대상에서 제외"),
    PARSE_FAILED("파싱 실패", "raw를 보존하고 parser 수정 후 재분석 가능");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileParseStatus(String titleKo, String descriptionKo) {
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
