package com.home.application.insight.collection;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.MarketInsightCoverage;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.time.Instant;

public interface RtmsCollectionExecutionRepository {

    void savePlan(RtmsCollectionExecutionPlan plan, Instant startedAt);

    RtmsCollectionWorkUnitState findWorkUnitState(ExecutionCorrelationId executionId, String lawdCd, String dealYmd);

    void markRunning(ExecutionCorrelationId executionId, String lawdCd, String dealYmd, Instant startedAt);

    void markTerminal(
            ExecutionCorrelationId executionId,
            String lawdCd,
            String dealYmd,
            RtmsCollectionWorkUnitState state,
            Long rtmsIngestRunId,
            Instant completedAt);

    MarketInsightCoverage finish(ExecutionCorrelationId executionId, Instant completedAt);
}
