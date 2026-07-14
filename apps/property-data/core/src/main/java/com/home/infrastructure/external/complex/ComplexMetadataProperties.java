package com.home.infrastructure.external.complex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("complex.metadata")
public record ComplexMetadataProperties(
        @Positive @DefaultValue("250") long minRequestIntervalMillis,
        @Positive @DefaultValue("5000") int connectTimeoutMillis,
        @Positive @DefaultValue("5000") int readTimeoutMillis,
        @Positive @DefaultValue("1000") int dailyRequestQuota,
        @Valid @DefaultValue Building building,
        @Valid @DefaultValue Enrich enrich) {

    public record Building(@DefaultValue("false") boolean enabled) {}

    public record Enrich(@Positive @DefaultValue("100") int batchSize) {}
}
