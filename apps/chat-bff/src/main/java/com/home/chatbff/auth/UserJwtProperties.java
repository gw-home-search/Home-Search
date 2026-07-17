package com.home.chatbff.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("home.chat-bff.jwt")
public record UserJwtProperties(String issuer, String audience, Duration maximumLifetime, String publicKeyPaths) {
    public UserJwtProperties {
        if (issuer == null
                || issuer.isBlank()
                || audience == null
                || audience.isBlank()
                || maximumLifetime == null
                || maximumLifetime.isZero()
                || maximumLifetime.isNegative()) {
            throw new IllegalArgumentException("canonical user JWT policy is required");
        }
        publicKeyPaths = publicKeyPaths == null ? "" : publicKeyPaths.trim();
    }
}
