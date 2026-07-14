package com.home.admin.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.admin.internal")
public record InternalAdminJwtProperties(
        boolean enabled,
        String issuer,
        String audience,
        @NotNull Duration tokenLifetime,
        String keyId,
        String privateKeyPath) {
    @AssertTrue(message = "internal admin JWT configuration is invalid")
    public boolean isValidWhenEnabled() {
        if (!enabled) return true;
        return notBlank(issuer)
                && notBlank(audience)
                && notBlank(keyId)
                && notBlank(privateKeyPath)
                && tokenLifetime != null
                && !tokenLifetime.isZero()
                && !tokenLifetime.isNegative()
                && tokenLifetime.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
