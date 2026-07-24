package com.home.application.insight.generation;

import com.home.domain.insight.MarketInsightCoveragePolicy;
import com.home.domain.insight.MarketInsightRejectionReason;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketInsightWeeklyBuildService {

    private final MarketInsightBuildRepository repository;
    private final Clock clock;

    public MarketInsightWeeklyBuildService(MarketInsightBuildRepository repository) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public MarketInsightBuildResult build(LocalDate weekStart) {
        Objects.requireNonNull(weekStart, "weekStart is required");
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart must be an ISO Monday");
        }
        List<MarketInsightSourceExecution> sources = repository.findLatestDailyNationwideForWeek(weekStart);
        if (!isCompleteWeek(weekStart, sources)) {
            return MarketInsightBuildResult.rejected(
                    repository.rejectWeeklyNationwide(
                            weekStart, sources, MarketInsightRejectionReason.INCOMPLETE_WORKSET, clock.instant()),
                    MarketInsightRejectionReason.INCOMPLETE_WORKSET);
        }
        for (MarketInsightSourceExecution source : sources) {
            var decision = MarketInsightCoveragePolicy.evaluate(source.coverage());
            if (!decision.publishable()) {
                return MarketInsightBuildResult.rejected(
                        repository.rejectWeeklyNationwide(weekStart, sources, decision.reason(), clock.instant()),
                        decision.reason());
            }
        }
        return MarketInsightBuildResult.published(
                repository.publishWeeklyNationwide(weekStart, sources, clock.instant()));
    }

    private boolean isCompleteWeek(LocalDate weekStart, List<MarketInsightSourceExecution> sources) {
        if (sources.size() != 7) return false;
        for (int day = 0; day < 7; day++) {
            if (!sources.get(day).runDate().equals(weekStart.plusDays(day))) return false;
        }
        return true;
    }
}
