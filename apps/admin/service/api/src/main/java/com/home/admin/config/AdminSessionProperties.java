package com.home.admin.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.admin.session")
public record AdminSessionProperties(@NotNull Duration absoluteLifetime) {
    @AssertTrue(message = "absolute session lifetime must be positive")
    public boolean isAbsoluteLifetimePositive() {
        return absoluteLifetime != null && !absoluteLifetime.isZero() && !absoluteLifetime.isNegative();
    }
}
