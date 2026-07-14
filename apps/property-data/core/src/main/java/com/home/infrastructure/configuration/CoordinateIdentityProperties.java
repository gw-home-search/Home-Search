package com.home.infrastructure.configuration;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("complex.coordinate.identity")
public record CoordinateIdentityProperties(
        @DefaultValue("true") boolean blockOnUnavailable,
        @DefaultValue("true") boolean blockOnFailed,
        @Positive @DefaultValue("5000") int connectTimeoutMillis,
        @Positive @DefaultValue("5000") int readTimeoutMillis) {}
