package com.home.infrastructure.external.odcloud;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("complex.identity")
public record ComplexIdentityProperties(
        @Positive @DefaultValue("5000") int connectTimeoutMillis,
        @Positive @DefaultValue("5000") int readTimeoutMillis) {}
