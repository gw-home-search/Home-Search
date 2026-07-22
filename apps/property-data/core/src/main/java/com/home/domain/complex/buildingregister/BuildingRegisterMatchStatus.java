package com.home.domain.complex.buildingregister;

public enum BuildingRegisterMatchStatus {
    RESOLVED("매칭 확정", "구조적이고 상호 유일한 근거로 root 범위를 확정"),
    AMBIGUOUS("매칭 모호", "허용된 exact 근거만으로 root를 확정할 수 없음"),
    INCOMPLETE_HIERARCHY("계층 불완전", "기대 표제부 집합이 완전하지 않음"),
    SOURCE_CONFLICT("원천 충돌", "동일 source identity의 내용이 충돌함"),
    AMBIGUOUS_GENERATION("신구대장 모호", "대장 세대를 하나로 확정할 수 없음"),
    SOURCE_MISSING("원천 누락", "매칭할 source scope가 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterMatchStatus(String titleKo, String descriptionKo) {
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
