package com.home.application.ingest.buildingprofile;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionResult;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterRequestBudget;
import com.home.application.ingest.buildingregister.BuildingRegisterRequestBudgetExceededException;
import com.home.domain.complex.buildingprofile.BuildingProfileCollectionPolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyFacts;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
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
public class BuildingProfileRepairService {
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;

    private final BuildingRegisterCollectionService collector;
    private final BuildingProfileRepairRepository repairs;
    private final BuildingProfileSampleRepository samples;
    private final BuildingProfileCollectionPolicy policy = new BuildingProfileCollectionPolicy();

    public BuildingProfileRepairService(
            BuildingRegisterCollectionService collector,
            BuildingProfileRepairRepository repairs,
            BuildingProfileSampleRepository samples) {
        this.collector = Objects.requireNonNull(collector);
        this.repairs = Objects.requireNonNull(repairs);
        this.samples = Objects.requireNonNull(samples);
    }

    public BuildingProfileRepairSummary repair(BuildingProfileRepairCommand command) {
        List<BuildingProfileCollectTarget> targets = repairs.freezeOrLoad(command);
        Set<String> completedPnus = samples.completedPnus(command.collectionId());
        List<BuildingProfileCollectTarget> pending = targets.stream()
                .filter(target -> !completedPnus.contains(target.pnu()))
                .toList();
        BuildingRegisterRequestBudget budget = new BuildingRegisterRequestBudget(command.maxRequests());
        ExecutorService executor = Executors.newFixedThreadPool(command.parallelism());
        CompletionService<PnuOutcome> completion = new ExecutorCompletionService<>(executor);
        int completed = 0;
        int failures = 0;
        int next = 0;
        int active = 0;
        try {
            while ((next < pending.size() && budget.remaining() > 0) || active > 0) {
                while (next < pending.size() && active < command.parallelism() && budget.remaining() > 0) {
                    BuildingProfileCollectTarget target = pending.get(next++);
                    if (repairs.transientFailureCount(command.collectionId(), target.pnu()) >= MAX_TRANSIENT_ATTEMPTS) {
                        throw new IllegalStateException("profile repair transient failure reached three attempts");
                    }
                    completion.submit(() -> repairPnu(command, target, budget));
                    active++;
                }
                if (active == 0) break;
                PnuOutcome outcome = completed(completion, budget);
                active--;
                if (outcome.completed()) completed++;
                if (outcome.failed()) failures++;
            }
        } finally {
            budget.stop();
            executor.shutdownNow();
        }
        boolean allCompleted = samples.completeIfAllPnusCollected(command.collectionId());
        repairs.recordProgress(command.collectionId(), budget.used(), completed, failures, allCompleted);
        return new BuildingProfileRepairSummary(targets.size(), budget.used(), completed, failures, allCompleted);
    }

    private PnuOutcome repairPnu(
            BuildingProfileRepairCommand command,
            BuildingProfileCollectTarget target,
            BuildingRegisterRequestBudget budget) {
        BuildingRegisterCollectionResult result;
        try {
            result = collector.collect(
                    new BuildingRegisterCollectCommand(
                            command.collectionId(),
                            command.requestId(),
                            command.runDate(),
                            target.pnu(),
                            target.complexCount(),
                            BuildingRegisterCollectionStrategy.COMPARE_RECAP_TITLE,
                            command.maxRequests(),
                            true),
                    budget);
        } catch (BuildingRegisterRequestBudgetExceededException exhausted) {
            return PnuOutcome.exhausted();
        }
        if (result.status() != BuildingRegisterCollectionStatus.COLLECTED) {
            samples.recordFailure(
                    command.collectionId(), target.pnu(), result.status().name());
            if (result.status() == BuildingRegisterCollectionStatus.PROVIDER_FAILED
                    && repairs.transientFailureCount(command.collectionId(), target.pnu()) >= MAX_TRANSIENT_ATTEMPTS) {
                throw new IllegalStateException("profile repair transient failure reached three attempts");
            }
            return PnuOutcome.failure();
        }
        samples.recordCollected(command.collectionId(), target.pnu(), hierarchyReasons(target, result));
        return PnuOutcome.success();
    }

    private Set<BuildingProfileHierarchyReason> hierarchyReasons(
            BuildingProfileCollectTarget target, BuildingRegisterCollectionResult result) {
        List<BuildingRegisterHierarchyRecord> records = java.util.stream.Stream.concat(
                        result.recapRecords().stream(), result.titleRecords().stream())
                .map(this::hierarchyRecord)
                .toList();
        return policy.decide(BuildingProfileHierarchyFacts.from(target.complexCount(), records))
                .basicOverviewReasons();
    }

    private BuildingRegisterHierarchyRecord hierarchyRecord(BuildingRegisterRecordSnapshotCommand record) {
        return new BuildingRegisterHierarchyRecord(
                record.endpoint(),
                record.managementKey(),
                record.parentManagementKey(),
                integer(record.registerKindCode()),
                record.newOldRegisterCode(),
                record.buildingName(),
                record.dongName());
    }

    private int integer(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private PnuOutcome completed(CompletionService<PnuOutcome> completion, BuildingRegisterRequestBudget budget) {
        try {
            return completion.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            budget.stop();
            throw new IllegalStateException("building profile repair interrupted", exception);
        } catch (ExecutionException exception) {
            budget.stop();
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("building profile repair failed", exception.getCause());
        }
    }

    private record PnuOutcome(boolean completed, boolean failed) {
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
