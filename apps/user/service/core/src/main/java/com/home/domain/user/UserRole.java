package com.home.domain.user;

public enum UserRole {
    USER("일반 사용자", "Home Search 일반 사용자");

    private final String titleKo;
    private final String descriptionKo;

    UserRole(String titleKo, String descriptionKo) {
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
