package com.home.application.insight.collection;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RtmsCollectionExecutionService implements RtmsCollectionExecutionTracker {

    private final RtmsCollectionExecutionRepository repository;
    private final Clock clock;

    public RtmsCollectionExecutionService(RtmsCollectionExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional
    public void plan(
            ExecutionCorrelationId executionId,
            RtmsCollectionMode mode,
            RtmsCollectionScopeType scopeType,
            LocalDate runDate,
            List<RtmsCollectionWorkUnitPlan> workUnits) {
        repository.savePlan(
                new RtmsCollectionExecutionPlan(executionId, mode, scopeType, runDate, workUnits), clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public RtmsCollectionWorkUnitState state(ExecutionCorrelationId executionId, String lawdCd, String dealYmd) {
        return repository.findWorkUnitState(executionId, lawdCd, dealYmd);
    }

    @Override
    @Transactional
    public void markRunning(ExecutionCorrelationId executionId, String lawdCd, String dealYmd) {
        repository.markRunning(executionId, lawdCd, dealYmd, clock.instant());
    }

    @Override
    @Transactional
    public void markTerminal(
            ExecutionCorrelationId executionId,
            String lawdCd,
            String dealYmd,
            RtmsCollectionWorkUnitState state,
            Long rtmsIngestRunId) {
        if (!Objects.requireNonNull(state, "state is required").terminal()) {
            throw new IllegalArgumentException("terminal work unit state is required");
        }
        repository.markTerminal(executionId, lawdCd, dealYmd, state, rtmsIngestRunId, clock.instant());
    }

    @Override
    @Transactional
    public MarketInsightCoverage finish(ExecutionCorrelationId executionId) {
        return repository.finish(executionId, clock.instant());
    }
}
