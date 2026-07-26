package com.home.application.news.collection;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsFailureKind;
import com.home.domain.news.MarketNewsRelationMatch;
import com.home.domain.news.MarketNewsWorkUnitState;
import com.home.domain.news.NewsRejectionReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketNewsCollectionRepository {

    default Optional<MarketNewsCollectionResult> findTerminalResult(String requestId) {
        return Optional.empty();
    }

    default Optional<MarketNewsCollectionExecution> findResumableExecution(String requestId) {
        return Optional.empty();
    }

    MarketNewsCollectionExecution planGeneral(
            String requestId, Instant scheduledAt, Instant overlapCutoff, int callBudget);

    MarketNewsCollectionExecution planMajorComplex(
            String requestId, Instant scheduledAt, Instant overlapCutoff, int callBudget);

    void startExecution(UUID executionId, Instant startedAt);

    void startWorkUnit(UUID workUnitId, Instant startedAt);

    void saveRawItems(UUID workUnitId, List<NewsProviderItem> items, Instant receivedAt);

    void requireRawItemMatch(UUID workUnitId, NewsProviderItem rawItem);

    void recordWorkUnitPageProgress(
            UUID workUnitId, int providerStart, int callCount, int rawItemCount, Instant oldestProvidedAt);

    long upsertArticle(NormalizedNewsItem item, Instant seenAt);

    void linkRawItem(UUID workUnitId, NewsProviderItem rawItem, long articleId);

    void rejectRawItem(UUID workUnitId, NewsProviderItem rawItem, NewsRejectionReason reason);

    void saveRelation(
            long articleId, String policyVersion, MarketNewsCategory category, MarketNewsRelationMatch relation);

    void finishWorkUnit(
            UUID workUnitId,
            MarketNewsWorkUnitState state,
            int callCount,
            int rawItemCount,
            Instant oldestProvidedAt,
            boolean cutoffReached,
            MarketNewsFailureKind failureKind,
            Instant completedAt);

    void incrementExecutionCallCount(UUID executionId);

    void finishExecution(
            UUID executionId, MarketNewsExecutionState state, MarketNewsFailureKind failureKind, Instant completedAt);

    List<PublishedNewsSnapshot> publishEligibleScopes(UUID executionId, Instant generatedAt);

    void markRemainingSkippedBudget(UUID executionId, Instant completedAt);
}
