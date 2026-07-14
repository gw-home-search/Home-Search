package com.home.user.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.jwt")
public record JwtProperties(
        @NotBlank String activeKid,
        @NotNull Path privateKeyPath,
        @NotNull Path activePublicKeyPath,
        String overlapKid,
        String overlapPublicKeyPath,
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration lifetime) {
    public JwtProperties {
        overlapKid = overlapKid == null ? "" : overlapKid;
        overlapPublicKeyPath = overlapPublicKeyPath == null ? "" : overlapPublicKeyPath;
    }

    @AssertTrue(message = "JWT lifetime must be positive")
    public boolean isLifetimePositive() {
        return lifetime != null && !lifetime.isZero() && !lifetime.isNegative();
    }
}
