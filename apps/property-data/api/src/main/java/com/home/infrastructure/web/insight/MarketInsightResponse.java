package com.home.infrastructure.web.insight;

import com.home.application.insight.read.MarketInsightReadResult;
import com.home.domain.insight.MarketInsightDataStatus;
import com.home.domain.insight.MarketInsightScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MarketInsightResponse(
        UUID snapshotId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant generatedAt,
        Instant dataCutoff,
        MarketInsightDataStatus dataStatus,
        ScopeResponse scope,
        QualityResponse quality,
        List<MarketInsightTradeItemResponse> newTrades,
        List<MarketInsightTradeItemResponse> highestDeals,
        List<MarketInsightTradeItemResponse> recordHighs,
        List<MarketInsightTradeItemResponse> previousRises,
        List<MarketInsightTradeItemResponse> previousFalls,
        List<MarketInsightTradeItemResponse> cancellations) {

    static MarketInsightResponse from(MarketInsightReadResult result) {
        return new MarketInsightResponse(
                result.snapshotId(),
                result.periodStart(),
                result.periodEnd(),
                result.generatedAt(),
                result.dataCutoff(),
                result.dataStatus(),
                new ScopeResponse(result.scopeType(), result.regionCode()),
                new QualityResponse(
                        result.quality().missingRegistrationDateCount(),
                        result.quality().invalidRegistrationDateCount(),
                        result.quality().missingCancellationDateCount(),
                        result.quality().invalidCancellationDateCount(),
                        result.quality().excludedCount()),
                items(result.newTrades()),
                items(result.highestDeals()),
                items(result.recordHighs()),
                items(result.previousRises()),
                items(result.previousFalls()),
                items(result.cancellations()));
    }

    private static List<MarketInsightTradeItemResponse> items(
            List<com.home.application.insight.read.MarketInsightTradeItemView> items) {
        return items.stream().map(MarketInsightTradeItemResponse::from).toList();
    }

    public record ScopeResponse(MarketInsightScopeType type, String regionCode) {}

    public record QualityResponse(
            int missingRegistrationDateCount,
            int invalidRegistrationDateCount,
            int missingCancellationDateCount,
            int invalidCancellationDateCount,
            int excludedCount) {}
}
