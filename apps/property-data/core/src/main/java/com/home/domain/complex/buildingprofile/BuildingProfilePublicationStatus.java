package com.home.domain.complex.buildingprofile;

public enum BuildingProfilePublicationStatus implements DescribedStoredValue {
    PREPARING("준비 중", "typed profile과 evidence를 적재하는 상태"),
    VALIDATED("검증 완료", "행 수와 digest 검증을 통과해 발행할 수 있는 상태"),
    PUBLISHED("발행 중", "공개 API와 운영 조회가 사용하는 현재 publication"),
    SUPERSEDED("대체됨", "새 publication 발행 후 이력으로 보존되는 상태"),
    FAILED("실패", "발행 전에 실패해 현재 publication에 영향을 주지 않는 상태");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfilePublicationStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public boolean canValidate() {
        return this == PREPARING;
    }

    public boolean canPublish() {
        return this == VALIDATED;
    }

    public boolean canSupersede() {
        return this == PUBLISHED;
    }

    public boolean isTerminalFailure() {
        return this == FAILED;
    }

    @Override
    public String titleKo() {
        return titleKo;
    }

    @Override
    public String descriptionKo() {
        return descriptionKo;
    }
}
