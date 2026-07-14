package com.home.application.auth;

import java.time.Duration;
import java.util.Objects;

public record RefreshTokenSettings(Duration ttl) {
    public RefreshTokenSettings {
        Objects.requireNonNull(ttl);
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Refresh token TTL must be positive");
    }
}
