package com.home.batch.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.ingest.rtms.RtmsMonthlyRefreshRunSummary;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.ingest.trade.IngestResult;
import com.home.application.insight.collection.RtmsCollectionExecutionTracker;
import com.home.application.insight.collection.RtmsCollectionWorkUnitPlan;
import com.home.application.region.RegionSiGunGuCodeReader;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.insight.RtmsCollectionMode;
import com.home.domain.insight.RtmsCollectionScopeType;
import com.home.domain.insight.RtmsCollectionWorkUnitState;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;

class RtmsMonthlyRefreshTaskletTest {

    @Test
    @DisplayName("monthly tasklet은 planned workset을 use case로 순차 실행하고 context에 snapshot을 저장한다")
    void taskletRunsPlannedWorksetSequentiallyAndStoresSnapshot() throws Exception {
        RtmsMonthlyRefreshUseCase useCase = mock(RtmsMonthlyRefreshUseCase.class);
        ExecutionCorrelationId correlationId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174004");
        when(useCase.refresh("11680", "202607", correlationId))
                .thenReturn(RtmsMonthlyRefreshRunSummary.completed(
                        "11680", "202607", 1, new IngestResult(1, 1, 1, 0, 0, 0), 1L));
        when(useCase.refresh("11680", "202606", correlationId))
                .thenReturn(RtmsMonthlyRefreshRunSummary.completed(
                        "11680", "202606", 1, new IngestResult(1, 1, 0, 1, 0, 0), 2L));
        RtmsMonthlyRefreshTasklet tasklet = new RtmsMonthlyRefreshTasklet(
                useCase, new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty()), "11680", 1, true);
        StepExecution stepExecution = stepExecution(new JobParameters(Set.of(
                new JobParameter<>("runDate", "2026-07-07", String.class, true),
                new JobParameter<>("requestId", correlationId.toString(), String.class, true))));

        tasklet.execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution)));

        assertThat(stepExecution.getJobExecution().getExecutionContext().getString("rtmsRefreshWorkset"))
                .isEqualTo("11680:202607;11680:202606");
        assertThat(stepExecution
                        .getJobExecution()
                        .getExecutionContext()
                        .get(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY))
                .isEqualTo(Boolean.FALSE);
        verify(useCase).refresh("11680", "202607", correlationId);
        verify(useCase).refresh("11680", "202606", correlationId);
    }

    @Test
    @DisplayName("DAILY tasklet은 첫 수집 전에 전체 targeted workset을 기록하고 run 결과로 terminal 전이한다")
    void taskletPersistsWholeWorksetBeforeFirstRefreshAndLinksTerminalRun() throws Exception {
        RtmsMonthlyRefreshUseCase useCase = mock(RtmsMonthlyRefreshUseCase.class);
        RtmsCollectionExecutionTracker tracker = mock(RtmsCollectionExecutionTracker.class);
        ExecutionCorrelationId correlationId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174005");
        when(useCase.refresh("11680", "202607", correlationId))
                .thenReturn(RtmsMonthlyRefreshRunSummary.completed(
                        "11680", "202607", 1, new IngestResult(0, 0, 0, 0, 0, 0), 91L));
        when(tracker.state(correlationId, "11680", "202607")).thenReturn(RtmsCollectionWorkUnitState.PLANNED);
        RtmsMonthlyRefreshTasklet tasklet = new RtmsMonthlyRefreshTasklet(
                useCase, new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty()), "11680", 0, true, tracker);
        StepExecution stepExecution = stepExecution(new JobParameters(Set.of(
                new JobParameter<>("runDate", "2026-07-07", String.class, true),
                new JobParameter<>("requestId", correlationId.toString(), String.class, true))));

        tasklet.execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution)));

        var ordered = inOrder(tracker, useCase);
        ordered.verify(tracker)
                .plan(
                        correlationId,
                        RtmsCollectionMode.DAILY,
                        RtmsCollectionScopeType.TARGETED,
                        LocalDate.parse("2026-07-07"),
                        List.of(new RtmsCollectionWorkUnitPlan("11680", "202607")));
        ordered.verify(tracker).state(correlationId, "11680", "202607");
        ordered.verify(tracker).markRunning(correlationId, "11680", "202607");
        ordered.verify(useCase).refresh("11680", "202607", correlationId);
        ordered.verify(tracker)
                .markTerminal(correlationId, "11680", "202607", RtmsCollectionWorkUnitState.COMPLETED, 91L);
        ordered.verify(tracker).finish(correlationId);
    }

    @Test
    @DisplayName("DAILY tasklet은 불완전 workset을 실패시켜 같은 JobInstance가 재시작되게 한다")
    void taskletFailsIncompleteWorksetSoSameJobInstanceCanRestart() {
        RtmsMonthlyRefreshUseCase useCase = mock(RtmsMonthlyRefreshUseCase.class);
        RtmsCollectionExecutionTracker tracker = mock(RtmsCollectionExecutionTracker.class);
        ExecutionCorrelationId correlationId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174006");
        when(useCase.refresh("11680", "202607", correlationId))
                .thenReturn(RtmsMonthlyRefreshRunSummary.failed(
                        "11680", "202607", 0, new IngestResult(0, 0, 0, 0, 0, 0), "Read timed out", 92L));
        when(tracker.state(correlationId, "11680", "202607")).thenReturn(RtmsCollectionWorkUnitState.PLANNED);
        RtmsMonthlyRefreshTasklet tasklet = new RtmsMonthlyRefreshTasklet(
                useCase, new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty()), "11680", 0, true, tracker);
        StepExecution stepExecution = stepExecution(new JobParameters(Set.of(
                new JobParameter<>("runDate", "2026-07-07", String.class, true),
                new JobParameter<>("requestId", correlationId.toString(), String.class, true))));

        assertThatThrownBy(() -> tasklet.execute(
                        new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RTMS workset is incomplete and must be restarted");

        assertThat(stepExecution
                        .getJobExecution()
                        .getExecutionContext()
                        .get(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY))
                .isEqualTo(Boolean.TRUE);
        verify(tracker).markTerminal(correlationId, "11680", "202607", RtmsCollectionWorkUnitState.FAILED, 92L);
        verify(tracker).finish(correlationId);
    }

    @Test
    @DisplayName("DAILY tasklet 재시작은 완료 단위를 보존하고 실패 단위만 다시 수집한다")
    void taskletRestartRetriesOnlyIncompleteWorkUnits() throws Exception {
        RtmsMonthlyRefreshUseCase useCase = mock(RtmsMonthlyRefreshUseCase.class);
        RtmsCollectionExecutionTracker tracker = mock(RtmsCollectionExecutionTracker.class);
        ExecutionCorrelationId correlationId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174007");
        when(tracker.state(correlationId, "11680", "202607")).thenReturn(RtmsCollectionWorkUnitState.COMPLETED);
        when(tracker.state(correlationId, "11680", "202606")).thenReturn(RtmsCollectionWorkUnitState.FAILED);
        when(useCase.refresh("11680", "202606", correlationId))
                .thenReturn(RtmsMonthlyRefreshRunSummary.completed(
                        "11680", "202606", 1, new IngestResult(0, 0, 0, 0, 0, 0), 93L));
        RtmsMonthlyRefreshTasklet tasklet = new RtmsMonthlyRefreshTasklet(
                useCase, new RtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader.empty()), "11680", 1, true, tracker);
        StepExecution stepExecution = stepExecution(new JobParameters(Set.of(
                new JobParameter<>("runDate", "2026-07-07", String.class, true),
                new JobParameter<>("requestId", correlationId.toString(), String.class, true))));

        tasklet.execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution)));

        verify(useCase, never()).refresh("11680", "202607", correlationId);
        verify(useCase).refresh("11680", "202606", correlationId);
        verify(tracker).markTerminal(correlationId, "11680", "202606", RtmsCollectionWorkUnitState.COMPLETED, 93L);
        verify(tracker).finish(correlationId);
    }

    private static StepExecution stepExecution(JobParameters parameters) {
        JobExecution jobExecution = new JobExecution(1L, new JobInstance(1L, "rtmsDailyRefreshJob"), parameters);
        return new StepExecution(1L, "monthlyIngestStep", jobExecution);
    }
}
