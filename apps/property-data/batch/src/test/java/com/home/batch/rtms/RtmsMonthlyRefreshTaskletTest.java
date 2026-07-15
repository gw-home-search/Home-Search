package com.home.batch.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.ingest.rtms.RtmsMonthlyRefreshRunSummary;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.ingest.trade.IngestResult;
import com.home.application.region.RegionSiGunGuCodeReader;
import com.home.domain.ingest.run.ExecutionCorrelationId;
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

    private static StepExecution stepExecution(JobParameters parameters) {
        JobExecution jobExecution = new JobExecution(1L, new JobInstance(1L, "rtmsDailyRefreshJob"), parameters);
        return new StepExecution(1L, "monthlyIngestStep", jobExecution);
    }
}
