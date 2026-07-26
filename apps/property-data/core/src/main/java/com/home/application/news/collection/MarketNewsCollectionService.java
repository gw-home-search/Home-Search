package com.home.application.news.collection;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsClassificationPolicy;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsRelationMatch;
import com.home.domain.news.MarketNewsRelationPolicy;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsWorkUnitKind;
import com.home.domain.news.MarketNewsWorkUnitState;
import com.home.domain.news.NewsRejectionReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MarketNewsCollectionService {

    private static final int DISPLAY = 100;
    private static final int MAX_START = 1000;
    private final MarketNewsCollectionRepository repository;
    private final NewsProviderGateway provider;
    private final NewsItemNormalizationGateway normalizer;
    private final MarketNewsPublicationCache publicationCache;
    private final MarketNewsClassificationPolicy classificationPolicy;
    private final MarketNewsRelationPolicy relationPolicy;
    private final Clock clock;

    public MarketNewsCollectionService(
            MarketNewsCollectionRepository repository,
            NewsProviderGateway provider,
            NewsItemNormalizationGateway normalizer,
            MarketNewsPublicationCache publicationCache) {
        this(
                repository,
                provider,
                normalizer,
                publicationCache,
                new MarketNewsClassificationPolicy(),
                new MarketNewsRelationPolicy(),
                Clock.systemUTC());
    }

    MarketNewsCollectionService(
            MarketNewsCollectionRepository repository,
            NewsProviderGateway provider,
            NewsItemNormalizationGateway normalizer,
            MarketNewsPublicationCache publicationCache,
            MarketNewsClassificationPolicy classificationPolicy,
            MarketNewsRelationPolicy relationPolicy,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.provider = Objects.requireNonNull(provider);
        this.normalizer = Objects.requireNonNull(normalizer);
        this.publicationCache = Objects.requireNonNull(publicationCache);
        this.classificationPolicy = Objects.requireNonNull(classificationPolicy);
        this.relationPolicy = Objects.requireNonNull(relationPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    public MarketNewsCollectionResult collectGeneral(String requestId, Instant scheduledAt, int callBudget) {
        var existing = repository.findTerminalResult(requestId);
        if (existing.isPresent()) return existing.get();
        var resumable = repository.findResumableExecution(requestId);
        if (resumable.isPresent()) return collect(resumable.get());
        Duration initialWindow =
                requestId != null && requestId.startsWith("BOOTSTRAP") ? Duration.ofDays(30) : Duration.ofHours(2);
        return collect(repository.planGeneral(requestId, scheduledAt, scheduledAt.minus(initialWindow), callBudget));
    }

    public MarketNewsCollectionResult collectMajorComplex(String requestId, Instant scheduledAt, int callBudget) {
        var existing = repository.findTerminalResult(requestId);
        if (existing.isPresent()) return existing.get();
        var resumable = repository.findResumableExecution(requestId);
        if (resumable.isPresent()) return collect(resumable.get());
        return collect(repository.planMajorComplex(
                requestId, scheduledAt, scheduledAt.minus(Duration.ofHours(2)), callBudget));
    }

    private MarketNewsCollectionResult collect(MarketNewsCollectionExecution execution) {
        Instant startedAt = clock.instant();
        repository.startExecution(execution.executionId(), startedAt);
        int calls = execution.consumedCallCount();
        int completed = execution.completedWorkUnitCount();
        int failed = execution.failedWorkUnitCount();
        int truncated = execution.truncatedWorkUnitCount();
        int skipped = execution.skippedBudgetWorkUnitCount();
        boolean stopForQuota = false;
        String executionFailure = null;
        for (MarketNewsWorkUnitSpec unit : execution.workUnits()) {
            if (stopForQuota || calls >= execution.callBudget()) {
                repository.markRemainingSkippedBudget(execution.executionId(), clock.instant());
                if (!stopForQuota) {
                    executionFailure = "DAILY_CALL_BUDGET";
                }
                break;
            }
            UnitResult result = collectUnit(execution, unit, calls);
            calls += result.callCount();
            completed += result.state() == MarketNewsWorkUnitState.COMPLETED ? 1 : 0;
            failed += result.state() == MarketNewsWorkUnitState.FAILED ? 1 : 0;
            truncated += result.state() == MarketNewsWorkUnitState.TRUNCATED ? 1 : 0;
            if ("DAILY_QUOTA".equals(result.failureKind())
                    || "AUTHENTICATION".equals(result.failureKind())
                    || "DAILY_CALL_BUDGET".equals(result.failureKind())) {
                stopForQuota = true;
                executionFailure = result.failureKind();
            }
        }
        if (stopForQuota || calls >= execution.callBudget()) {
            skipped += Math.max(0, execution.plannedWorkUnitCount() - completed - failed - truncated - skipped);
        }
        boolean bootstrapHasCollectedRange =
                "BOOTSTRAP".equals(execution.executionType()) && truncated > 0 && failed == 0;
        MarketNewsExecutionState state =
                failed == 0 && truncated == 0 && skipped == 0 && completed == execution.plannedWorkUnitCount()
                        ? MarketNewsExecutionState.COMPLETED
                        : completed > 0 || bootstrapHasCollectedRange
                                ? MarketNewsExecutionState.PARTIAL
                                : MarketNewsExecutionState.FAILED;
        if (state == MarketNewsExecutionState.COMPLETED || state == MarketNewsExecutionState.PARTIAL) {
            for (PublishedNewsSnapshot snapshot :
                    repository.publishEligibleScopes(execution.executionId(), clock.instant())) {
                try {
                    publicationCache.publish(snapshot);
                } catch (RuntimeException cacheFailure) {
                    executionFailure = "CACHE_PUBLICATION";
                }
            }
        }
        repository.finishExecution(execution.executionId(), state, executionFailure, clock.instant());
        return new MarketNewsCollectionResult(execution.executionId(), state, calls, completed, failed, truncated);
    }

    private UnitResult collectUnit(
            MarketNewsCollectionExecution execution, MarketNewsWorkUnitSpec unit, int callsBeforeUnit) {
        repository.startWorkUnit(unit.workUnitId(), clock.instant());
        UnitCallCounter unitCalls = new UnitCallCounter();
        int rawCount = unit.collectedRawItemCount();
        int start = unit.nextProviderStart();
        Instant oldest = unit.oldestProvidedAt();
        boolean cutoffReached = false;
        MarketNewsRelationPolicy.IndexedCorpus relationIndex = relationPolicy.index(unit.matchingCorpus());
        try {
            while (start <= MAX_START && callsBeforeUnit + unitCalls.value < execution.callBudget()) {
                NewsProviderPage page = callWithSingleRetry(
                        new NewsProviderQuery(unit.query(), start, DISPLAY),
                        execution.executionId(),
                        execution.callBudget() - callsBeforeUnit,
                        unitCalls);
                rawCount += page.items().size();
                repository.saveRawItems(unit.workUnitId(), page.items(), clock.instant());
                for (NewsProviderItem raw : page.items()) {
                    repository.requireRawItemMatch(unit.workUnitId(), raw);
                    Instant rawProvidedAt = normalizer.tryParseProvidedAt(raw).orElse(null);
                    if (rawProvidedAt != null && (oldest == null || rawProvidedAt.isBefore(oldest))) {
                        oldest = rawProvidedAt;
                    }
                    var normalized = normalizer.tryNormalize(raw);
                    if (!normalized.accepted()) {
                        repository.rejectRawItem(unit.workUnitId(), raw, normalized.rejectionReason());
                        continue;
                    }
                    NormalizedNewsItem item = normalized.item();
                    if (oldest == null || item.providedAt().isBefore(oldest)) {
                        oldest = item.providedAt();
                    }
                    processNormalized(execution, unit, relationIndex, raw, item);
                }
                repository.recordWorkUnitPageProgress(
                        unit.workUnitId(), start, unit.collectedCallCount() + unitCalls.value, rawCount, oldest);
                if (page.items().isEmpty() || (oldest != null && !oldest.isAfter(execution.overlapCutoff()))) {
                    cutoffReached = true;
                    break;
                }
                start += DISPLAY;
            }
            MarketNewsWorkUnitState state =
                    cutoffReached ? MarketNewsWorkUnitState.COMPLETED : MarketNewsWorkUnitState.TRUNCATED;
            repository.finishWorkUnit(
                    unit.workUnitId(),
                    state,
                    unit.collectedCallCount() + unitCalls.value,
                    rawCount,
                    oldest,
                    cutoffReached,
                    state == MarketNewsWorkUnitState.TRUNCATED ? "CUTOFF_NOT_REACHED" : null,
                    clock.instant());
            return new UnitResult(
                    state, unitCalls.value, state == MarketNewsWorkUnitState.TRUNCATED ? "CUTOFF_NOT_REACHED" : null);
        } catch (NewsCallBudgetExceededException exception) {
            repository.finishWorkUnit(
                    unit.workUnitId(),
                    MarketNewsWorkUnitState.SKIPPED_BUDGET,
                    unit.collectedCallCount() + unitCalls.value,
                    rawCount,
                    oldest,
                    false,
                    "DAILY_CALL_BUDGET",
                    clock.instant());
            return new UnitResult(MarketNewsWorkUnitState.SKIPPED_BUDGET, unitCalls.value, "DAILY_CALL_BUDGET");
        } catch (NewsProviderCallException exception) {
            repository.finishWorkUnit(
                    unit.workUnitId(),
                    MarketNewsWorkUnitState.FAILED,
                    unit.collectedCallCount() + unitCalls.value,
                    rawCount,
                    oldest,
                    false,
                    exception.type().name(),
                    clock.instant());
            return new UnitResult(
                    MarketNewsWorkUnitState.FAILED,
                    unitCalls.value,
                    exception.type().name());
        } catch (RawNewsPositionConflictException exception) {
            repository.finishWorkUnit(
                    unit.workUnitId(),
                    MarketNewsWorkUnitState.FAILED,
                    unit.collectedCallCount() + unitCalls.value,
                    rawCount,
                    oldest,
                    false,
                    "RAW_POSITION_CONFLICT",
                    clock.instant());
            return new UnitResult(MarketNewsWorkUnitState.FAILED, unitCalls.value, "RAW_POSITION_CONFLICT");
        } catch (RuntimeException exception) {
            repository.finishWorkUnit(
                    unit.workUnitId(),
                    MarketNewsWorkUnitState.FAILED,
                    unit.collectedCallCount() + unitCalls.value,
                    rawCount,
                    oldest,
                    false,
                    "INTERNAL",
                    clock.instant());
            return new UnitResult(MarketNewsWorkUnitState.FAILED, unitCalls.value, "INTERNAL");
        }
    }

    private NewsProviderPage callWithSingleRetry(
            NewsProviderQuery query, java.util.UUID executionId, int unitCallBudget, UnitCallCounter counter) {
        try {
            repository.incrementExecutionCallCount(executionId);
            counter.value++;
            return provider.search(query);
        } catch (NewsProviderCallException first) {
            if (!first.retryableOnce() || counter.value >= unitCallBudget) {
                throw first;
            }
            waitBeforeRetry(first);
            repository.incrementExecutionCallCount(executionId);
            counter.value++;
            return provider.search(query);
        }
    }

    private void waitBeforeRetry(NewsProviderCallException failure) {
        Integer seconds = failure.retryAfterSeconds();
        if (seconds == null || seconds == 0) {
            return;
        }
        if (seconds > 60) {
            throw failure;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NewsProviderCallException(
                    NewsProviderFailureType.TRANSIENT, "news provider retry interrupted", null, exception);
        }
    }

    private void processNormalized(
            MarketNewsCollectionExecution execution,
            MarketNewsWorkUnitSpec unit,
            MarketNewsRelationPolicy.IndexedCorpus relationIndex,
            NewsProviderItem raw,
            NormalizedNewsItem item) {
        Instant now = clock.instant();
        if (item.providedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            repository.rejectRawItem(unit.workUnitId(), raw, NewsRejectionReason.INVALID_PROVIDED_AT);
            return;
        }
        if (item.providedAt().isBefore(now.minus(Duration.ofDays(30)))) {
            repository.rejectRawItem(unit.workUnitId(), raw, NewsRejectionReason.OUTSIDE_RETENTION_WINDOW);
            return;
        }
        var category = classificationPolicy.classify(item.title(), item.description());
        if (category.isEmpty()) {
            repository.rejectRawItem(unit.workUnitId(), raw, NewsRejectionReason.NOT_REAL_ESTATE_RELEVANT);
            return;
        }
        List<MarketNewsRelationMatch> matches = new ArrayList<>();
        if (unit.kind() == MarketNewsWorkUnitKind.NATIONAL_CATEGORY) {
            matches.add(new MarketNewsRelationMatch(MarketNewsRelationType.NATIONWIDE, null, null, List.of()));
        } else if (unit.kind() == MarketNewsWorkUnitKind.SIDO && containsArticleText(item, unit.regionName())) {
            matches.add(new MarketNewsRelationMatch(
                    MarketNewsRelationType.SAME_SIDO, unit.regionCode(), null, List.of(unit.regionName())));
        }
        matches.addAll(relationPolicy.match(item.title(), item.description(), relationIndex));
        if (unit.kind() == MarketNewsWorkUnitKind.MAJOR_COMPLEX
                && matches.stream().noneMatch(match -> match.relationType() == MarketNewsRelationType.DIRECT_COMPLEX)) {
            repository.rejectRawItem(unit.workUnitId(), raw, NewsRejectionReason.COMPLEX_AMBIGUOUS);
            return;
        }
        if (matches.isEmpty()) {
            repository.rejectRawItem(unit.workUnitId(), raw, NewsRejectionReason.REGION_AMBIGUOUS);
            return;
        }
        long articleId = repository.upsertArticle(item, clock.instant());
        repository.linkRawItem(unit.workUnitId(), raw, articleId);
        MarketNewsCategory resolvedCategory = category.get();
        for (MarketNewsRelationMatch match : matches) {
            repository.saveRelation(articleId, execution.policyVersion(), resolvedCategory, match);
        }
    }

    private boolean containsArticleText(NormalizedNewsItem item, String token) {
        return token != null && (item.title() + " " + item.description()).contains(token);
    }

    private record UnitResult(MarketNewsWorkUnitState state, int callCount, String failureKind) {}

    private static final class UnitCallCounter {
        private int value;
    }
}
