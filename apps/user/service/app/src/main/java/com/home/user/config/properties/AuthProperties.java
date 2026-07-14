package com.home.user.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.auth")
public record AuthProperties(
        @NotNull URI allowedOrigin, @NotNull Duration refreshTtl) {
    @AssertTrue(message = "refresh TTL must be positive")
    public boolean isRefreshTtlPositive() {
        return refreshTtl != null && !refreshTtl.isZero() && !refreshTtl.isNegative();
    }
}
