package com.home.application.insight.generation;

import com.home.domain.insight.MarketInsightCoveragePolicy;
import com.home.domain.insight.MarketInsightRejectionReason;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketInsightRolling7dBuildService {

    private final MarketInsightBuildRepository repository;
    private final Clock clock;

    public MarketInsightRolling7dBuildService(MarketInsightBuildRepository repository) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public MarketInsightBuildResult build(LocalDate runDate) {
        Objects.requireNonNull(runDate, "runDate is required");
        MarketInsightSourceExecution source =
                repository.findLatestDailyNationwide(runDate).orElse(null);
        if (source == null) {
            var snapshotId = repository.rejectRolling7dNationwide(
                    runDate, null, MarketInsightRejectionReason.INCOMPLETE_WORKSET, clock.instant());
            return MarketInsightBuildResult.rejected(snapshotId, MarketInsightRejectionReason.INCOMPLETE_WORKSET);
        }
        var decision = MarketInsightCoveragePolicy.evaluate(source.coverage());
        if (!decision.publishable()) {
            var snapshotId = repository.rejectRolling7dNationwide(runDate, source, decision.reason(), clock.instant());
            return MarketInsightBuildResult.rejected(snapshotId, decision.reason());
        }
        return MarketInsightBuildResult.published(repository.publishRolling7dNationwide(source, clock.instant()));
    }
}
