package com.home.infrastructure.persistence.ingest;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.trade.partition.maintenance")
public record TradePartitionProperties(
        @Positive @DefaultValue("5") int yearsAhead) {}
