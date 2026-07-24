package com.home.domain.complex.buildingregister;

public enum BuildingRegisterHierarchyStatus {
    RESOLVED("계층 확인", "root와 하위 표제부 범위를 확인함"),
    INCOMPLETE_HIERARCHY("계층 불완전", "기대 관리번호가 표제부 응답에 없음"),
    SOURCE_CONFLICT("원천 충돌", "동일 관리번호가 서로 다른 내용으로 중복됨"),
    AMBIGUOUS_GENERATION("신구대장 모호", "사용할 신대장 세대를 하나로 확정할 수 없음"),
    SOURCE_MISSING("원천 누락", "root 또는 단독 표제부를 구성할 수 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterHierarchyStatus(String titleKo, String descriptionKo) {
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
