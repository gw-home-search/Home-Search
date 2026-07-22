package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioEvaluationContext;
import com.home.domain.complex.buildingregister.BuildingRatioEvaluator;
import com.home.domain.complex.buildingregister.BuildingRatioScope;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatchPolicy;
import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyPolicy;
import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyRecord;
import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterMatchStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterRecord;
import com.home.domain.complex.buildingregister.BuildingRegisterSourceScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BuildingRegisterCampaignService {
    private final BuildingRegisterCollectionService collectionService;
    private final BuildingRegisterCampaignRepository campaigns;
    private final BuildingRatioCandidateRepository candidates;
    private final BuildingRegisterHierarchyPolicy hierarchy = new BuildingRegisterHierarchyPolicy();
    private final BuildingRegisterComplexMatchPolicy matcher = new BuildingRegisterComplexMatchPolicy();
    private final BuildingRatioEvaluator evaluator = new BuildingRatioEvaluator();

    public BuildingRegisterCampaignService(
            BuildingRegisterCollectionService collectionService,
            BuildingRegisterCampaignRepository campaigns,
            BuildingRatioCandidateRepository candidates) {
        this.collectionService = Objects.requireNonNull(collectionService);
        this.campaigns = Objects.requireNonNull(campaigns);
        this.candidates = Objects.requireNonNull(candidates);
    }

    public BuildingRegisterCampaignSummary collect(BuildingRegisterCampaignCommand command) {
        List<BuildingRegisterCampaignTarget> targets = campaigns.freezeOrLoad(command);
        Map<String, List<BuildingRegisterCampaignTarget>> byPnu = targets.stream()
                .collect(Collectors.groupingBy(
                        BuildingRegisterCampaignTarget::pnu, LinkedHashMap::new, Collectors.toList()));
        Set<String> fullyMatchedPnus = campaigns.isCompleted(command.collectionId())
                ? Set.of()
                : campaigns.fullyMatchedPnus(command.collectionId());
        if (command.parallelism() > 1) {
            return collectParallel(command, targets.size(), byPnu, fullyMatchedPnus);
        }
        int requests = 0;
        int matches = 0;
        for (var entry : byPnu.entrySet()) {
            if (fullyMatchedPnus.contains(entry.getKey())) continue;
            int remaining = command.maxRequests() - requests;
            if (remaining <= 0) break;
            BuildingRegisterCollectionResult collected;
            try {
                collected = collectionService.collect(new BuildingRegisterCollectCommand(
                        command.collectionId(),
                        command.requestId(),
                        command.runDate(),
                        entry.getKey(),
                        entry.getValue().size(),
                        command.strategy(),
                        remaining));
            } catch (BuildingRegisterRequestBudgetExceededException exhausted) {
                requests += exhausted.consumedRequests();
                break;
            }
            requests += collected.requestCount();
            if (collected.status() != BuildingRegisterCollectionStatus.COLLECTED) continue;
            matches += evaluatePnu(command, entry.getKey(), entry.getValue(), collected);
        }
        boolean completed = campaigns.completeIfAllTargetsMatched(command.collectionId());
        return new BuildingRegisterCampaignSummary(targets.size(), byPnu.size(), requests, matches, completed);
    }

    private BuildingRegisterCampaignSummary collectParallel(
            BuildingRegisterCampaignCommand command,
            int targetCount,
            Map<String, List<BuildingRegisterCampaignTarget>> byPnu,
            Set<String> fullyMatchedPnus) {
        ExecutorService executor = Executors.newFixedThreadPool(command.parallelism());
        CompletionService<PnuCollectionResult> completion = new ExecutorCompletionService<>(executor);
        var entries = byPnu.entrySet().iterator();
        int submitted = 0;
        int completedTasks = 0;
        int requests = 0;
        int matches = 0;
        try {
            while (entries.hasNext() || completedTasks < submitted) {
                while (entries.hasNext()
                        && submitted - completedTasks < command.parallelism()
                        && requests + submitted - completedTasks < command.maxRequests()) {
                    var entry = entries.next();
                    if (fullyMatchedPnus.contains(entry.getKey())) continue;
                    completion.submit(() -> collectPnu(command, entry.getKey(), entry.getValue()));
                    submitted++;
                }
                if (completedTasks == submitted) break;
                PnuCollectionResult result = completed(completion);
                completedTasks++;
                requests += result.requests();
                matches += result.matches();
                if (requests >= command.maxRequests()) break;
            }
        } finally {
            executor.shutdownNow();
        }
        boolean campaignCompleted = campaigns.completeIfAllTargetsMatched(command.collectionId());
        return new BuildingRegisterCampaignSummary(targetCount, byPnu.size(), requests, matches, campaignCompleted);
    }

    private PnuCollectionResult collectPnu(
            BuildingRegisterCampaignCommand command, String pnu, List<BuildingRegisterCampaignTarget> targets) {
        BuildingRegisterCollectionResult collected;
        try {
            collected = collectionService.collect(command.collectCommand(pnu, targets.size(), 1));
        } catch (BuildingRegisterRequestBudgetExceededException exhausted) {
            return new PnuCollectionResult(exhausted.consumedRequests(), 0);
        }
        int matches = collected.status() == BuildingRegisterCollectionStatus.COLLECTED
                ? evaluatePnu(command, pnu, targets, collected)
                : 0;
        return new PnuCollectionResult(collected.requestCount(), matches);
    }

    private PnuCollectionResult completed(CompletionService<PnuCollectionResult> completion) {
        try {
            return completion.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("building register parallel collection interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("building register parallel collection failed", exception.getCause());
        }
    }

    private int evaluatePnu(
            BuildingRegisterCampaignCommand command,
            String pnu,
            List<BuildingRegisterCampaignTarget> targets,
            BuildingRegisterCollectionResult collected) {
        var hierarchyResult = hierarchy.resolve(hierarchyRecords(collected));
        if (hierarchyResult.status() != BuildingRegisterHierarchyStatus.RESOLVED) {
            BuildingRegisterMatchStatus status = matchStatus(hierarchyResult.status());
            for (BuildingRegisterCampaignTarget target : targets) {
                campaigns.recordMatch(
                        command.collectionId(),
                        pnu,
                        targets.size(),
                        new BuildingRegisterComplexMatch(
                                target.complexId(),
                                null,
                                BuildingRatioScope.UNIQUE_ROOT,
                                status,
                                null,
                                false,
                                hierarchyResult.reason()));
            }
            return targets.size();
        }
        List<BuildingRegisterComplexMatch> matches = matcher.match(
                targets.stream()
                        .map(BuildingRegisterCampaignTarget::matchTarget)
                        .toList(),
                hierarchyResult.scopes());
        Map<String, BuildingRegisterSourceScope> scopeByKey = hierarchyResult.scopes().stream()
                .collect(Collectors.toMap(BuildingRegisterSourceScope::rootManagementKey, scope -> scope));
        Map<String, Long> sourceRecordIds = campaigns.sourceRecordIds(command.collectionId(), pnu);
        for (BuildingRegisterComplexMatch match : matches) {
            long matchId = campaigns.recordMatch(command.collectionId(), pnu, targets.size(), match);
            if (match.status() != BuildingRegisterMatchStatus.RESOLVED || match.rootManagementKey() == null) continue;
            BuildingRegisterSourceScope scope = scopeByKey.get(match.rootManagementKey());
            if (scope == null) continue;
            var evaluation = evaluate(command.strategy(), match.scope(), scope, collected);
            candidates.record(matchId, evaluation, sourceRecordIds);
        }
        return matches.size();
    }

    private com.home.domain.complex.buildingregister.BuildingRatioEvaluation evaluate(
            BuildingRegisterCollectionStrategy strategy,
            BuildingRatioScope matchedScope,
            BuildingRegisterSourceScope scope,
            BuildingRegisterCollectionResult collected) {
        if (matchedScope == BuildingRatioScope.STANDALONE_TITLE) {
            BuildingRegisterRecord title = collected.titleRecords().stream()
                    .filter(record -> scope.rootManagementKey().equals(record.managementKey()))
                    .findFirst()
                    .map(this::ratioRecord)
                    .orElseThrow();
            return evaluator.evaluate(BuildingRatioEvaluationContext.standalone(title));
        }
        BuildingRegisterRecord recap = collected.recapRecords().stream()
                .filter(record -> scope.rootManagementKey().equals(record.managementKey()))
                .findFirst()
                .map(this::ratioRecord)
                .orElseThrow();
        List<BuildingRegisterRecord> titles =
                collected.titleRecords().stream().map(this::ratioRecord).toList();
        BuildingRatioEvaluationContext context = matchedScope == BuildingRatioScope.SHARED_RECAP
                ? BuildingRatioEvaluationContext.sharedRoot(
                        strategy, recap, titles, scope.expectedManagementKeys(), scope.hierarchyComplete())
                : BuildingRatioEvaluationContext.uniqueRoot(
                        strategy, recap, titles, scope.expectedManagementKeys(), scope.hierarchyComplete());
        return evaluator.evaluate(context);
    }

    private List<BuildingRegisterHierarchyRecord> hierarchyRecords(BuildingRegisterCollectionResult result) {
        List<BuildingRegisterRecordSnapshotCommand> all = new ArrayList<>();
        all.addAll(result.recapRecords());
        all.addAll(result.titleRecords());
        all.addAll(result.basicOverviewRecords());
        return all.stream()
                .map(record -> new BuildingRegisterHierarchyRecord(
                        record.endpoint(),
                        record.managementKey(),
                        record.parentManagementKey(),
                        integer(record.registerKindCode()),
                        record.newOldRegisterCode(),
                        record.buildingName(),
                        record.dongName()))
                .toList();
    }

    private BuildingRegisterRecord ratioRecord(BuildingRegisterRecordSnapshotCommand record) {
        return new BuildingRegisterRecord(
                record.managementKey(),
                record.parentManagementKey(),
                integer(record.registerKindCode()),
                record.mainAttachedCode(),
                record.mainPurposeCode(),
                record.platArea(),
                record.archArea(),
                record.totalArea(),
                record.floorRatioEstimateTotalArea(),
                record.buildingCoverageRatio(),
                record.floorAreaRatio());
    }

    private int integer(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private BuildingRegisterMatchStatus matchStatus(BuildingRegisterHierarchyStatus status) {
        return switch (status) {
            case RESOLVED -> BuildingRegisterMatchStatus.RESOLVED;
            case INCOMPLETE_HIERARCHY -> BuildingRegisterMatchStatus.INCOMPLETE_HIERARCHY;
            case SOURCE_CONFLICT -> BuildingRegisterMatchStatus.SOURCE_CONFLICT;
            case AMBIGUOUS_GENERATION -> BuildingRegisterMatchStatus.AMBIGUOUS_GENERATION;
            case SOURCE_MISSING -> BuildingRegisterMatchStatus.SOURCE_MISSING;
        };
    }

    private record PnuCollectionResult(int requests, int matches) {}
}
