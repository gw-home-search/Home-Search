package com.home.batch.insight;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.home.application.insight.generation.MarketInsightBuildResult;
import com.home.application.insight.generation.MarketInsightDailyBuildService;
import com.home.application.insight.generation.MarketInsightRolling7dBuildService;
import com.home.domain.insight.MarketInsightRejectionReason;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;

class MarketInsightTaskletTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 7, 23);

    @Test
    void dailyRejectionFailsTheStepSoRollingCannotStart() {
        MarketInsightDailyBuildService service = mock(MarketInsightDailyBuildService.class);
        when(service.build(RUN_DATE))
                .thenReturn(MarketInsightBuildResult.rejected(
                        UUID.randomUUID(), MarketInsightRejectionReason.INCOMPLETE_WORKSET));

        assertThatThrownBy(() -> new MarketInsightDailyTasklet(service).execute(contribution(), context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INCOMPLETE_WORKSET");
    }

    @Test
    void rollingRejectionFailsTheStandaloneJob() {
        MarketInsightRolling7dBuildService service = mock(MarketInsightRolling7dBuildService.class);
        when(service.build(RUN_DATE))
                .thenReturn(MarketInsightBuildResult.rejected(
                        UUID.randomUUID(), MarketInsightRejectionReason.NON_SUCCESSFUL_WORK_UNIT));

        assertThatThrownBy(() -> new MarketInsightRolling7dTasklet(service).execute(contribution(), context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NON_SUCCESSFUL_WORK_UNIT");
    }

    @Test
    void publishedResultCompletesBothTasklets() {
        MarketInsightDailyBuildService daily = mock(MarketInsightDailyBuildService.class);
        MarketInsightRolling7dBuildService rolling = mock(MarketInsightRolling7dBuildService.class);
        when(daily.build(RUN_DATE)).thenReturn(MarketInsightBuildResult.published(UUID.randomUUID()));
        when(rolling.build(RUN_DATE)).thenReturn(MarketInsightBuildResult.published(UUID.randomUUID()));

        assertThatCode(() -> new MarketInsightDailyTasklet(daily).execute(contribution(), context()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MarketInsightRolling7dTasklet(rolling).execute(contribution(), context()))
                .doesNotThrowAnyException();
    }

    private StepExecution stepExecution() {
        var parameters = new JobParametersBuilder()
                .addString("runDate", RUN_DATE.toString())
                .toJobParameters();
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "rtmsDailyRefreshJob"), parameters);
        return new StepExecution(1L, "marketInsightStep", execution);
    }

    private StepContribution contribution() {
        return new StepContribution(stepExecution());
    }

    private ChunkContext context() {
        return new ChunkContext(new StepContext(stepExecution()));
    }
}
