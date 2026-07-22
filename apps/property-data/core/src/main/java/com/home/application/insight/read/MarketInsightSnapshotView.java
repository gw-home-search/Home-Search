package com.home.application.insight.read;

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
        List<MarketInsightTradeItemView> items) {

    public MarketInsightSnapshotView {
        items = List.copyOf(items);
    }
}
