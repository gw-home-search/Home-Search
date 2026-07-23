package com.home.application.ingest.buildingprofile;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionResult;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterRequestBudget;
import com.home.application.ingest.buildingregister.BuildingRegisterRequestBudgetExceededException;
import com.home.domain.complex.buildingprofile.BuildingProfileCodeTransitionPolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileCollectionPolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyFacts;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
import com.home.domain.complex.buildingprofile.BuildingProfileLookupResult;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyRecord;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class BuildingProfileCollectionService {
    private final BuildingRegisterCollectionService collector;
    private final BuildingProfileSampleRepository samples;
    private final BuildingProfileCollectionPolicy policy = new BuildingProfileCollectionPolicy();
    private final BuildingProfileCodeTransitionPolicy codeTransitionPolicy = new BuildingProfileCodeTransitionPolicy();

    public BuildingProfileCollectionService(
            BuildingRegisterCollectionService collector, BuildingProfileSampleRepository samples) {
        this.collector = Objects.requireNonNull(collector);
        this.samples = Objects.requireNonNull(samples);
    }

    public BuildingProfileCollectSummary collect(BuildingProfileCollectCommand command) {
        List<BuildingProfileCollectTarget> targets = samples.freezeOrLoad(command);
        Set<String> completedPnus = samples.completedPnus(command.collectionId());
        List<BuildingProfileCollectTarget> pending = targets.stream()
                .filter(target -> !completedPnus.contains(target.pnu()))
                .toList();
        BuildingRegisterRequestBudget budget = new BuildingRegisterRequestBudget(command.maxRequests());
        ExecutorService executor = Executors.newFixedThreadPool(command.parallelism());
        CompletionService<PnuOutcome> completion = new ExecutorCompletionService<>(executor);
        int collected = 0;
        int failures = 0;
        int next = 0;
        int active = 0;
        try {
            while ((next < pending.size() && budget.remaining() > 0) || active > 0) {
                while (next < pending.size() && active < command.parallelism() && budget.remaining() > 0) {
                    BuildingProfileCollectTarget target = pending.get(next++);
                    completion.submit(() -> collectPnu(command, target, budget));
                    active++;
                }
                if (active == 0) break;
                PnuOutcome outcome = completed(completion, budget);
                active--;
                if (outcome.collected()) collected++;
                if (outcome.failed()) failures++;
            }
        } finally {
            budget.stop();
            executor.shutdownNow();
        }
        boolean complete = samples.completeIfAllPnusCollected(command.collectionId());
        return new BuildingProfileCollectSummary(targets.size(), budget.used(), collected, failures, complete);
    }

    private BuildingRegisterCollectionResult collect(
            BuildingProfileCollectCommand command, String pnu, int complexCount, BuildingRegisterRequestBudget budget) {
        return collector.collect(
                new BuildingRegisterCollectCommand(
                        command.collectionId(),
                        command.requestId(),
                        command.runDate(),
                        pnu,
                        complexCount,
                        BuildingRegisterCollectionStrategy.COMPARE_RECAP_TITLE,
                        command.maxRequests(),
                        command.targetScope().isValidationSample()),
                budget);
    }

    private PnuOutcome collectPnu(
            BuildingProfileCollectCommand command,
            BuildingProfileCollectTarget target,
            BuildingRegisterRequestBudget budget) {
        BuildingRegisterCollectionResult result;
        try {
            result = collect(command, target.pnu(), target.complexCount(), budget);
        } catch (BuildingRegisterRequestBudgetExceededException exhausted) {
            return PnuOutcome.exhausted();
        }
        if (result.status() != BuildingRegisterCollectionStatus.COLLECTED) {
            samples.recordFailure(
                    command.collectionId(), target.pnu(), result.status().name());
            return PnuOutcome.failure();
        }
        var transition = samples.codeTransition(target.pnu());
        if (transition.isPresent()) {
            BuildingRegisterCollectionResult candidate;
            try {
                candidate = collect(command, transition.get().candidatePnu(), target.complexCount(), budget);
            } catch (BuildingRegisterRequestBudgetExceededException exhausted) {
                return PnuOutcome.exhausted();
            }
            Set<String> oldKeys = managementKeys(result);
            Set<String> newKeys = managementKeys(candidate);
            BuildingProfileLookupResult oldLookup = lookupResult(result);
            BuildingProfileLookupResult newLookup = lookupResult(candidate);
            samples.recordCodeLookup(
                    command.collectionId(),
                    new BuildingProfileCodeLookupEvidence(
                            command.requestId(),
                            transition.get().importId(),
                            target.pnu(),
                            transition.get().candidatePnu(),
                            oldLookup,
                            newLookup,
                            codeTransitionPolicy.compare(oldLookup, oldKeys, newLookup, newKeys),
                            oldKeys,
                            newKeys));
            if (candidate.status() != BuildingRegisterCollectionStatus.COLLECTED) {
                samples.recordFailure(
                        command.collectionId(),
                        target.pnu(),
                        "CODE_TRANSITION_" + candidate.status().name());
                return PnuOutcome.failure();
            }
        }
        samples.recordCollected(command.collectionId(), target.pnu(), hierarchyReasons(target, result));
        return PnuOutcome.success();
    }

    private PnuOutcome completed(CompletionService<PnuOutcome> completion, BuildingRegisterRequestBudget budget) {
        try {
            return completion.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            budget.stop();
            throw new IllegalStateException("building profile collection interrupted", exception);
        } catch (ExecutionException exception) {
            budget.stop();
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("building profile collection failed", exception.getCause());
        }
    }

    private BuildingProfileLookupResult lookupResult(BuildingRegisterCollectionResult result) {
        if (result.status() == BuildingRegisterCollectionStatus.PROVIDER_FAILED) {
            return BuildingProfileLookupResult.PROVIDER_FAILED;
        }
        if (result.status() == BuildingRegisterCollectionStatus.PARSE_FAILED) {
            return BuildingProfileLookupResult.PARSE_FAILED;
        }
        return managementKeys(result).isEmpty()
                ? BuildingProfileLookupResult.EMPTY
                : BuildingProfileLookupResult.SUCCESS;
    }

    private Set<String> managementKeys(BuildingRegisterCollectionResult result) {
        return java.util.stream.Stream.concat(result.recapRecords().stream(), result.titleRecords().stream())
                .map(BuildingRegisterRecordSnapshotCommand::managementKey)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<BuildingProfileHierarchyReason> hierarchyReasons(
            BuildingProfileCollectTarget target, BuildingRegisterCollectionResult result) {
        List<BuildingRegisterHierarchyRecord> records = java.util.stream.Stream.concat(
                        result.recapRecords().stream(), result.titleRecords().stream())
                .map(record -> new BuildingRegisterHierarchyRecord(
                        record.endpoint(),
                        record.managementKey(),
                        record.parentManagementKey(),
                        integer(record.registerKindCode()),
                        record.newOldRegisterCode(),
                        record.buildingName(),
                        record.dongName()))
                .toList();
        return policy.decide(BuildingProfileHierarchyFacts.from(target.complexCount(), records))
                .basicOverviewReasons();
    }

    private int integer(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record PnuOutcome(boolean collected, boolean failed) {
        static PnuOutcome success() {
            return new PnuOutcome(true, false);
        }

        static PnuOutcome failure() {
            return new PnuOutcome(false, true);
        }

        static PnuOutcome exhausted() {
            return new PnuOutcome(false, false);
        }
    }
}
