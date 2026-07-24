package com.home.application.insight.collection;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.time.LocalDate;
import java.util.List;

public interface RtmsCollectionExecutionTracker {

    void plan(
            ExecutionCorrelationId executionId,
            RtmsCollectionMode mode,
            RtmsCollectionScopeType scopeType,
            LocalDate runDate,
            List<RtmsCollectionWorkUnitPlan> workUnits);

    RtmsCollectionWorkUnitState state(ExecutionCorrelationId executionId, String lawdCd, String dealYmd);

    void markRunning(ExecutionCorrelationId executionId, String lawdCd, String dealYmd);

    void markTerminal(
            ExecutionCorrelationId executionId,
            String lawdCd,
            String dealYmd,
            RtmsCollectionWorkUnitState state,
            Long rtmsIngestRunId);

    MarketInsightCoverage finish(ExecutionCorrelationId executionId);

    static RtmsCollectionExecutionTracker noop() {
        return new RtmsCollectionExecutionTracker() {
            @Override
            public void plan(
                    ExecutionCorrelationId executionId,
                    RtmsCollectionMode mode,
                    RtmsCollectionScopeType scopeType,
                    LocalDate runDate,
                    List<RtmsCollectionWorkUnitPlan> workUnits) {}

            @Override
            public RtmsCollectionWorkUnitState state(
                    ExecutionCorrelationId executionId, String lawdCd, String dealYmd) {
                return RtmsCollectionWorkUnitState.PLANNED;
            }

            @Override
            public void markRunning(ExecutionCorrelationId executionId, String lawdCd, String dealYmd) {}

            @Override
            public void markTerminal(
                    ExecutionCorrelationId executionId,
                    String lawdCd,
                    String dealYmd,
                    RtmsCollectionWorkUnitState state,
                    Long rtmsIngestRunId) {}

            @Override
            public MarketInsightCoverage finish(ExecutionCorrelationId executionId) {
                return null;
            }
        };
    }
}
