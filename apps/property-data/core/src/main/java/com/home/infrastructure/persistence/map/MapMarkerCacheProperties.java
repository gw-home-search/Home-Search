package com.home.infrastructure.persistence.map;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.map.marker-cache")
public record MapMarkerCacheProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration ttl) {

    public MapMarkerCacheProperties {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("home.map.marker-cache.ttl must be positive");
        }
    }
}
