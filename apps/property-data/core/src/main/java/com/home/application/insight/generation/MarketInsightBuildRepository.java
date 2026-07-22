package com.home.application.insight.generation;

import com.home.domain.insight.MarketInsightRejectionReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface MarketInsightBuildRepository {

    Optional<MarketInsightSourceExecution> findLatestDailyNationwide(LocalDate runDate);

    UUID publishDailyNationwide(MarketInsightSourceExecution source, Instant generatedAt);

    UUID rejectDailyNationwide(
            LocalDate runDate,
            MarketInsightSourceExecution source,
            MarketInsightRejectionReason reason,
            Instant generatedAt);
}
