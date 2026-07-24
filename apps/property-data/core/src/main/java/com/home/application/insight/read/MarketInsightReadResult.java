package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightDataStatus;
import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightQuality;
import com.home.domain.insight.MarketInsightScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MarketInsightReadResult(
        UUID snapshotId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant generatedAt,
        Instant dataCutoff,
        MarketInsightDataStatus dataStatus,
        MarketInsightScopeType scopeType,
        String regionCode,
        MarketInsightQuality quality,
        List<MarketInsightTradeItemView> newTrades,
        List<MarketInsightTradeItemView> highestDeals,
        List<MarketInsightTradeItemView> recordHighs,
        List<MarketInsightTradeItemView> previousRises,
        List<MarketInsightTradeItemView> previousFalls,
        List<MarketInsightTradeItemView> cancellations) {

    public MarketInsightReadResult(
            UUID snapshotId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant generatedAt,
            Instant dataCutoff,
            MarketInsightDataStatus dataStatus,
            MarketInsightScopeType scopeType,
            String regionCode,
            List<MarketInsightTradeItemView> newTrades,
            List<MarketInsightTradeItemView> highestDeals,
            List<MarketInsightTradeItemView> recordHighs,
            List<MarketInsightTradeItemView> previousRises,
            List<MarketInsightTradeItemView> previousFalls,
            List<MarketInsightTradeItemView> cancellations) {
        this(
                snapshotId,
                periodStart,
                periodEnd,
                generatedAt,
                dataCutoff,
                dataStatus,
                scopeType,
                regionCode,
                MarketInsightQuality.NONE,
                newTrades,
                highestDeals,
                recordHighs,
                previousRises,
                previousFalls,
                cancellations);
    }

    public static MarketInsightReadResult unavailable(
            MarketInsightScopeType scopeType, String regionCode, LocalDate requestedDate) {
        return new MarketInsightReadResult(
                null,
                requestedDate,
                requestedDate,
                null,
                null,
                MarketInsightDataStatus.UNAVAILABLE,
                scopeType,
                regionCode,
                MarketInsightQuality.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public static MarketInsightReadResult unavailableWeekly(
            MarketInsightScopeType scopeType, String regionCode, LocalDate weekStart) {
        return new MarketInsightReadResult(
                null,
                weekStart,
                weekStart.plusDays(6),
                null,
                null,
                MarketInsightDataStatus.UNAVAILABLE,
                scopeType,
                regionCode,
                MarketInsightQuality.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public static MarketInsightReadResult from(MarketInsightSnapshotView snapshot, LocalDate requestedDate) {
        List<MarketInsightTradeItemView> items = snapshot.items();
        return new MarketInsightReadResult(
                snapshot.snapshotId(),
                snapshot.periodStart(),
                snapshot.periodEnd(),
                snapshot.generatedAt(),
                snapshot.dataCutoff(),
                snapshot.periodStart().equals(requestedDate)
                        ? MarketInsightDataStatus.FRESH
                        : MarketInsightDataStatus.STALE,
                snapshot.scopeType(),
                snapshot.regionCode(),
                snapshot.quality(),
                metric(items, MarketInsightMetricType.DAILY_NEW_TRADE),
                metric(items, MarketInsightMetricType.DAILY_HIGHEST_DEAL),
                metric(items, MarketInsightMetricType.AREA_RECORD_HIGH),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_RISE),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_FALL),
                metric(items, MarketInsightMetricType.CANCELLATION_CORRECTION));
    }

    public static MarketInsightReadResult fromWeekly(MarketInsightSnapshotView snapshot, LocalDate requestedWeekStart) {
        List<MarketInsightTradeItemView> items = snapshot.items();
        return new MarketInsightReadResult(
                snapshot.snapshotId(),
                snapshot.periodStart(),
                snapshot.periodEnd(),
                snapshot.generatedAt(),
                snapshot.dataCutoff(),
                snapshot.periodStart().equals(requestedWeekStart)
                        ? MarketInsightDataStatus.FRESH
                        : MarketInsightDataStatus.STALE,
                snapshot.scopeType(),
                snapshot.regionCode(),
                snapshot.quality(),
                metric(items, MarketInsightMetricType.WEEKLY_NEW_TRADE),
                metric(items, MarketInsightMetricType.WEEKLY_HIGHEST_DEAL),
                metric(items, MarketInsightMetricType.AREA_RECORD_HIGH),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_RISE),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_FALL),
                metric(items, MarketInsightMetricType.CANCELLATION_CORRECTION));
    }

    public static MarketInsightReadResult unavailableRolling(
            MarketInsightScopeType scopeType, String regionCode, LocalDate asOfDate) {
        return new MarketInsightReadResult(
                null,
                asOfDate.minusDays(6),
                asOfDate,
                null,
                null,
                MarketInsightDataStatus.UNAVAILABLE,
                scopeType,
                regionCode,
                MarketInsightQuality.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public static MarketInsightReadResult fromRolling(MarketInsightSnapshotView snapshot) {
        List<MarketInsightTradeItemView> items = snapshot.items();
        return new MarketInsightReadResult(
                snapshot.snapshotId(),
                snapshot.periodStart(),
                snapshot.periodEnd(),
                snapshot.generatedAt(),
                snapshot.dataCutoff(),
                snapshot.sourceCurrent() ? MarketInsightDataStatus.FRESH : MarketInsightDataStatus.STALE,
                snapshot.scopeType(),
                snapshot.regionCode(),
                snapshot.quality(),
                metric(items, MarketInsightMetricType.ROLLING_7D_NEW_TRADE),
                metric(items, MarketInsightMetricType.ROLLING_7D_HIGHEST_DEAL),
                metric(items, MarketInsightMetricType.AREA_RECORD_HIGH),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_RISE),
                metric(items, MarketInsightMetricType.AREA_PREVIOUS_FALL),
                metric(items, MarketInsightMetricType.CANCELLATION_CORRECTION));
    }

    private static List<MarketInsightTradeItemView> metric(
            List<MarketInsightTradeItemView> items, MarketInsightMetricType metricType) {
        return items.stream().filter(item -> item.metricType() == metricType).toList();
    }
}
