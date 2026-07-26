package com.home.batch.news;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.news.collection.MarketNewsCollectionResult;
import com.home.application.news.collection.MarketNewsCollectionService;
import com.home.application.news.quality.MarketNewsQualitySamplingService;
import com.home.application.news.quality.MarketNewsQualityService;
import com.home.application.news.retention.MarketNewsRetentionService;
import com.home.application.news.selection.MajorNewsComplexSelectionService;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsWithdrawalReason;
import java.nio.charset.StandardCharsets;
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

class MarketNewsTaskletTest {

    private static final String REQUEST_ID = "BOOTSTRAP:123e4567-e89b-12d3-a456-426614174800";
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 7, 24);

    @Test
    void completedGeneralAndMajorCollectionsFinishTheirSteps() {
        MarketNewsCollectionService service = mock(MarketNewsCollectionService.class);
        when(service.collectGeneral(
                        org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000)))
                .thenReturn(result(MarketNewsExecutionState.COMPLETED));
        when(service.collectMajorComplex(
                        org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000)))
                .thenReturn(result(MarketNewsExecutionState.COMPLETED));

        assertThatCode(() -> new MarketNewsCollectionTasklet(service, 4000, false).execute(contribution(), context()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MarketNewsCollectionTasklet(service, 4000, true).execute(contribution(), context()))
                .doesNotThrowAnyException();
    }

    @Test
    void incompleteCollectionFailsTheStep() {
        MarketNewsCollectionService service = mock(MarketNewsCollectionService.class);
        String incrementalRequestId = "123e4567-e89b-12d3-a456-426614174801";
        when(service.collectGeneral(
                        org.mockito.ArgumentMatchers.eq(incrementalRequestId),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000)))
                .thenReturn(result(MarketNewsExecutionState.PARTIAL));

        assertThatThrownBy(() -> new MarketNewsCollectionTasklet(service, 4000, false)
                        .execute(contribution(incrementalRequestId), context(incrementalRequestId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARTIAL");
    }

    @Test
    void truncatedBootstrapPublicationFinishesTheStep() {
        MarketNewsCollectionService service = mock(MarketNewsCollectionService.class);
        when(service.collectGeneral(
                        org.mockito.ArgumentMatchers.eq(REQUEST_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000)))
                .thenReturn(new MarketNewsCollectionResult(
                        UUID.randomUUID(), MarketNewsExecutionState.PARTIAL, 10, 0, 0, 1));

        assertThatCode(() -> new MarketNewsCollectionTasklet(service, 4000, false).execute(contribution(), context()))
                .doesNotThrowAnyException();
    }

    @Test
    void morningMajorStepDerivesAnIndependentIdempotencyKey() {
        MarketNewsCollectionService service = mock(MarketNewsCollectionService.class);
        String morningRequestId = "123e4567-e89b-12d3-a456-426614174804";
        String majorRequestId = UUID.nameUUIDFromBytes(
                        ("market-news-major:" + morningRequestId).getBytes(StandardCharsets.UTF_8))
                .toString();
        when(service.collectMajorComplex(
                        org.mockito.ArgumentMatchers.eq(majorRequestId),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000)))
                .thenReturn(result(MarketNewsExecutionState.COMPLETED));

        assertThatCode(() -> new MarketNewsCollectionTasklet(service, 4000, true, true)
                        .execute(contribution(morningRequestId), context(morningRequestId)))
                .doesNotThrowAnyException();
        verify(service)
                .collectMajorComplex(
                        org.mockito.ArgumentMatchers.eq(majorRequestId),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4000));
    }

    @Test
    void selectionAndRetentionDelegateOnce() {
        MajorNewsComplexSelectionService selection = mock(MajorNewsComplexSelectionService.class);
        MarketNewsRetentionService retention = mock(MarketNewsRetentionService.class);

        assertThatCode(() -> new MarketNewsMajorSelectionTasklet(selection).execute(contribution(), context()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MarketNewsRetentionTasklet(retention).execute(contribution(), context()))
                .doesNotThrowAnyException();

        verify(selection).select(RUN_DATE);
        verify(retention).run();
    }

    @Test
    void withdrawalDelegatesValidatedSnapshotAndReason() {
        MarketNewsQualityService quality = mock(MarketNewsQualityService.class);
        UUID snapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174802");
        var parameters = new JobParametersBuilder()
                .addString("snapshotId", snapshotId.toString())
                .addString("reason", "RELATION_ACCURACY_BELOW_THRESHOLD")
                .toJobParameters();
        JobExecution execution = new JobExecution(2L, new JobInstance(2L, "marketNewsWithdrawalJob"), parameters);
        StepExecution stepExecution = new StepExecution(2L, "marketNewsWithdrawalStep", execution);

        assertThatCode(() -> new MarketNewsWithdrawalTasklet(quality)
                        .execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution))))
                .doesNotThrowAnyException();
        verify(quality).withdraw(snapshotId, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD);
    }

    @Test
    void qualitySampleDelegatesValidatedReviewSetAndPolicyVersion() {
        MarketNewsQualitySamplingService quality = mock(MarketNewsQualitySamplingService.class);
        UUID reviewSetId = UUID.fromString("123e4567-e89b-12d3-a456-426614174803");
        var parameters = new JobParametersBuilder()
                .addString("reviewSetId", reviewSetId.toString())
                .addString("policyVersion", "NEWS_V2")
                .toJobParameters();
        JobExecution execution = new JobExecution(3L, new JobInstance(3L, "marketNewsQualitySampleJob"), parameters);
        StepExecution stepExecution = new StepExecution(3L, "marketNewsQualitySampleStep", execution);

        assertThatCode(() -> new MarketNewsQualitySampleTasklet(quality)
                        .execute(new StepContribution(stepExecution), new ChunkContext(new StepContext(stepExecution))))
                .doesNotThrowAnyException();
        verify(quality).sample(reviewSetId, "NEWS_V2");
    }

    private MarketNewsCollectionResult result(MarketNewsExecutionState state) {
        return new MarketNewsCollectionResult(UUID.randomUUID(), state, 1, 1, 0, 0);
    }

    private StepExecution stepExecution() {
        return stepExecution(REQUEST_ID);
    }

    private StepExecution stepExecution(String requestId) {
        var parameters = new JobParametersBuilder()
                .addString("requestId", requestId)
                .addString("runDate", RUN_DATE.toString())
                .toJobParameters();
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "marketNewsJob"), parameters);
        return new StepExecution(1L, "marketNewsStep", execution);
    }

    private StepContribution contribution() {
        return new StepContribution(stepExecution());
    }

    private StepContribution contribution(String requestId) {
        return new StepContribution(stepExecution(requestId));
    }

    private ChunkContext context() {
        return new ChunkContext(new StepContext(stepExecution()));
    }

    private ChunkContext context(String requestId) {
        return new ChunkContext(new StepContext(stepExecution(requestId)));
    }
}
