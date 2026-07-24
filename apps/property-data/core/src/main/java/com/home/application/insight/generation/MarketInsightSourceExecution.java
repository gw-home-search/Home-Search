package com.home.application.insight.generation;

import com.home.domain.insight.MarketInsightCoverage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MarketInsightSourceExecution(
        UUID executionId, LocalDate runDate, Instant completedAt, MarketInsightCoverage coverage) {}
