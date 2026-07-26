package com.home.infrastructure.external.news;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.news.naver")
public record NaverNewsProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("API_HUB") NaverNewsProviderMode providerMode,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,

        @NotNull @DefaultValue("https://naverapihub.apigw.ntruss.com")
        URI baseUrl,

        @NotNull @DefaultValue("/search/v1/news") String path,
        @Min(1) @Max(4000) @DefaultValue("4000") int dailyCallBudget,
        @DefaultValue("true") boolean cacheEnabled,
        @NotNull @DefaultValue("31d") Duration cacheTtl,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("5s") Duration readTimeout) {

    public NaverNewsProperties {
        if (providerMode == null) {
            throw new IllegalArgumentException("home.news.naver.provider-mode must be configured");
        }
        clientId = clientId == null ? "" : clientId.trim();
        clientSecret = clientSecret == null ? "" : clientSecret.trim();
        if (enabled && (clientId.isBlank() || clientSecret.isBlank())) {
            throw new IllegalArgumentException("home.news.naver credentials must be configured when enabled=true");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("home.news.naver.path must start with /");
        }
        requirePositive(connectTimeout, "home.news.naver.connect-timeout");
        requirePositive(readTimeout, "home.news.naver.read-timeout");
        requirePositive(cacheTtl, "home.news.naver.cache-ttl");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
