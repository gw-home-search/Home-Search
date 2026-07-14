package com.home.infrastructure.persistence.ingest;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.ingest.match-rematch")
public record TradeMatchProperties(
        @Positive @DefaultValue("100") int batchSize) {}
