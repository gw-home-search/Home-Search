package com.home.infrastructure.external.news;

public enum NaverNewsProviderMode {
    API_HUB("X-NCP-APIGW-API-KEY-ID", "X-NCP-APIGW-API-KEY"),
    DEVELOPERS("X-Naver-Client-Id", "X-Naver-Client-Secret");

    private final String clientIdHeader;
    private final String clientSecretHeader;

    NaverNewsProviderMode(String clientIdHeader, String clientSecretHeader) {
        this.clientIdHeader = clientIdHeader;
        this.clientSecretHeader = clientSecretHeader;
    }

    public String clientIdHeader() {
        return clientIdHeader;
    }

    public String clientSecretHeader() {
        return clientSecretHeader;
    }
}
