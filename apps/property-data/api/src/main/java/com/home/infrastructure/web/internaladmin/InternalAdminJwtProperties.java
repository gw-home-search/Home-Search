package com.home.infrastructure.web.internaladmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.admin.internal")
public record InternalAdminJwtProperties(
        @NotBlank @DefaultValue("admin-service") String issuer,
        @NotBlank @DefaultValue("property-data-admin") String audience,
        @NotNull @DefaultValue("60s") Duration maximumLifetime,
        @NotBlank String publicKeys) {

    public InternalAdminJwtProperties {
        if (maximumLifetime != null && (maximumLifetime.isZero() || maximumLifetime.isNegative())) {
            throw new IllegalArgumentException("home.admin.internal.maximum-lifetime must be positive");
        }
    }
}
