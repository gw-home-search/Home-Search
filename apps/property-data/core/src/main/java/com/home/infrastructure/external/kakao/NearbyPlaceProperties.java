package com.home.infrastructure.external.kakao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.place.kakao")
public record NearbyPlaceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String restApiKey,
        @NotNull @DefaultValue("https://dapi.kakao.com") URI baseUrl,
        @Valid @DefaultValue Cache cache,
        @Min(1) @DefaultValue("10000") int dailyRequestBudget,
        @NotNull @DefaultValue("1s") Duration connectTimeout,
        @NotNull @DefaultValue("2s") Duration readTimeout,
        @NotNull @DefaultValue("5s") Duration totalTimeout,
        @Valid @DefaultValue Executor executor) {

    public NearbyPlaceProperties {
        restApiKey = restApiKey == null ? "" : restApiKey.trim();
        if (enabled && restApiKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "home.place.kakao.rest-api-key must be configured when home.place.kakao.enabled=true");
        }
        requirePositive(connectTimeout, "home.place.kakao.connect-timeout");
        requirePositive(readTimeout, "home.place.kakao.read-timeout");
        requirePositive(totalTimeout, "home.place.kakao.total-timeout");
    }

    private static void requirePositive(Duration value, String property) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("24h") Duration ttl,
            @NotNull @DefaultValue("1h") Duration viewportTtl) {

        public Cache {
            requirePositive(ttl, "home.place.kakao.cache.ttl");
            requirePositive(viewportTtl, "home.place.kakao.cache.viewport-ttl");
        }
    }

    public record Executor(
            @Min(1) @Max(4) @DefaultValue("4") int threads,
            @Min(1) @Max(120) @DefaultValue("24") int queueCapacity,
            @NotNull @DefaultValue("10s") Duration shutdownAwait) {

        public Executor {
            requirePositive(shutdownAwait, "home.place.kakao.executor.shutdown-await");
        }
    }
}
