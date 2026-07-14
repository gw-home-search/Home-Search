package com.home.batch.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.region.RegionUnitCntSynchronizationService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;

class RtmsTaskletTest {

    @Test
    @DisplayName("coordinate preflight tasklet은 preflight 실패를 그대로 전파한다")
    void coordinatePreflightTaskletPropagatesFailure() {
        RtmsCoordinatePreflightTasklet tasklet = new RtmsCoordinatePreflightTasklet(() -> {
            throw new IllegalStateException("coordinate missing");
        });
        StepExecution stepExecution = stepExecution("coordinatePreflightStep");

        assertThatThrownBy(() -> tasklet.execute(
                        new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coordinate");
    }

    @Test
    @DisplayName("region sync tasklet은 sync 실패를 warning flag로 남기고 step warning status를 설정한다")
    void regionSyncTaskletMapsFailureToWarning() throws Exception {
        RegionUnitCntSynchronizationService service = new RegionUnitCntSynchronizationService(() -> {
            throw new IllegalStateException("sync failed");
        });
        RtmsRegionUnitSyncTasklet tasklet = new RtmsRegionUnitSyncTasklet(service);
        StepExecution stepExecution = stepExecution("regionUnitSyncStep");

        tasklet.execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution)));

        assertThat(stepExecution
                        .getJobExecution()
                        .getExecutionContext()
                        .get(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("region sync tasklet은 service가 없으면 skip한다")
    void regionSyncTaskletSkipsWhenServiceIsMissing() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        RtmsRegionUnitSyncTasklet tasklet = new RtmsRegionUnitSyncTasklet(null);
        StepExecution stepExecution = stepExecution("regionUnitSyncStep");

        tasklet.execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution)));

        assertThat(called).isFalse();
        assertThat(stepExecution
                        .getJobExecution()
                        .getExecutionContext()
                        .containsKey(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY))
                .isFalse();
    }

    private static StepExecution stepExecution(String stepName) {
        JobExecution jobExecution =
                new JobExecution(1L, new JobInstance(1L, "rtmsDailyRefreshJob"), new JobParameters());
        return new StepExecution(1L, stepName, jobExecution);
    }
}
