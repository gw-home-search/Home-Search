package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightQuality;
import com.home.domain.insight.MarketInsightScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MarketInsightSnapshotView(
        UUID snapshotId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant generatedAt,
        Instant dataCutoff,
        MarketInsightScopeType scopeType,
        String regionCode,
        MarketInsightQuality quality,
        boolean sourceCurrent,
        List<MarketInsightTradeItemView> items) {

    public MarketInsightSnapshotView {
        quality = quality == null ? MarketInsightQuality.NONE : quality;
        items = List.copyOf(items);
    }

    public MarketInsightSnapshotView(
            UUID snapshotId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant generatedAt,
            Instant dataCutoff,
            MarketInsightScopeType scopeType,
            String regionCode,
            List<MarketInsightTradeItemView> items) {
        this(
                snapshotId,
                periodStart,
                periodEnd,
                generatedAt,
                dataCutoff,
                scopeType,
                regionCode,
                MarketInsightQuality.NONE,
                true,
                items);
    }
}
