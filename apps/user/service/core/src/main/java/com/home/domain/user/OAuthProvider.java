package com.home.domain.user;

public enum OAuthProvider {
    GOOGLE("구글", "Google OIDC 계정"),
    KAKAO("카카오", "Kakao OAuth 계정"),
    NAVER("네이버", "Naver OAuth 계정");

    private final String titleKo;
    private final String descriptionKo;

    OAuthProvider(String titleKo, String descriptionKo) {
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
