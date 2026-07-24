package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightScopeType;
import java.time.LocalDate;
import java.util.Optional;

public interface MarketInsightReadRepository {
    boolean existsRootSidoCode(String regionCode);

    Optional<MarketInsightSnapshotView> findLatestDaily(
            MarketInsightScopeType scopeType, String regionCode, LocalDate onOrBefore, int limit);

    Optional<MarketInsightSnapshotView> findLatestWeekly(
            MarketInsightScopeType scopeType, String regionCode, LocalDate onOrBeforeWeekStart, int limit);

    Optional<MarketInsightSnapshotView> findLatestRolling7d(
            MarketInsightScopeType scopeType, String regionCode, int limit);
}
