package com.home.domain.complex.buildingprofile;

public enum BuildingProfileLookupResult {
    SUCCESS("성공", "관리번호 record가 하나 이상 있음"),
    EMPTY("정상 빈 응답", "recap과 title이 모두 정상 empty"),
    PROVIDER_FAILED("provider 실패", "HTTP 또는 provider 실패"),
    PARSE_FAILED("parse 실패", "응답을 안정적으로 비교할 수 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileLookupResult(String titleKo, String descriptionKo) {
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
