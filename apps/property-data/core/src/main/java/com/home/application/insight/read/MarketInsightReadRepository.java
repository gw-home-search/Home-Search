package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightScopeType;
import java.time.LocalDate;
import java.util.Optional;

public interface MarketInsightReadRepository {
    Optional<MarketInsightSnapshotView> findLatestDaily(
            MarketInsightScopeType scopeType, String regionCode, LocalDate onOrBefore, int limit);
}
