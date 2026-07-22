package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightScopeType;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketInsightQueryService {

    private final MarketInsightReadRepository repository;

    public MarketInsightQueryService(MarketInsightReadRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(readOnly = true)
    public MarketInsightReadResult latest(
            MarketInsightScopeType scopeType, String regionCode, LocalDate requestedDate, int limit) {
        validate(scopeType, regionCode, requestedDate, limit);
        return repository
                .findLatestDaily(scopeType, regionCode, requestedDate, limit)
                .map(snapshot -> MarketInsightReadResult.from(snapshot, requestedDate))
                .orElseGet(() -> MarketInsightReadResult.unavailable(scopeType, regionCode, requestedDate));
    }

    private void validate(MarketInsightScopeType scopeType, String regionCode, LocalDate requestedDate, int limit) {
        Objects.requireNonNull(scopeType, "scopeType is required");
        Objects.requireNonNull(requestedDate, "requestedDate is required");
        if (limit < 1 || limit > 50) {
            throw new InvalidInsightQueryException("limit must be between 1 and 50");
        }
        boolean hasRegion = regionCode != null && !regionCode.isBlank();
        if ((scopeType == MarketInsightScopeType.SIDO) != hasRegion) {
            throw new InvalidInsightQueryException("regionCode is required only for SIDO scope");
        }
    }
}
