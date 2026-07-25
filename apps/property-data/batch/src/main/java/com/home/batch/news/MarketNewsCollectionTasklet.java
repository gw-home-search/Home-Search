package com.home.batch.news;

import com.home.application.news.collection.MarketNewsCollectionResult;
import com.home.application.news.collection.MarketNewsCollectionService;
import com.home.domain.news.MarketNewsExecutionState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketNewsCollectionTasklet implements Tasklet {

    private final MarketNewsCollectionService service;
    private final int callBudget;
    private final boolean majorComplex;
    private final boolean deriveMajorRequestId;

    MarketNewsCollectionTasklet(MarketNewsCollectionService service, int callBudget, boolean majorComplex) {
        this(service, callBudget, majorComplex, false);
    }

    MarketNewsCollectionTasklet(
            MarketNewsCollectionService service, int callBudget, boolean majorComplex, boolean deriveMajorRequestId) {
        this.service = service;
        this.callBudget = callBudget;
        this.majorComplex = majorComplex;
        this.deriveMajorRequestId = deriveMajorRequestId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String requestId = contribution.getStepExecution().getJobParameters().getString("requestId");
        if (majorComplex && deriveMajorRequestId) {
            requestId = UUID.nameUUIDFromBytes(("market-news-major:" + requestId).getBytes(StandardCharsets.UTF_8))
                    .toString();
        }
        MarketNewsCollectionResult result = majorComplex
                ? service.collectMajorComplex(requestId, Instant.now(), callBudget)
                : service.collectGeneral(requestId, Instant.now(), callBudget);
        boolean boundedBootstrapPublication = !majorComplex
                && requestId.startsWith("BOOTSTRAP:")
                && result.state() == MarketNewsExecutionState.PARTIAL
                && result.failedWorkUnits() == 0
                && result.truncatedWorkUnits() > 0;
        if (result.state() != MarketNewsExecutionState.COMPLETED && !boundedBootstrapPublication) {
            throw new IllegalStateException("news collection did not complete: " + result.state());
        }
        return RepeatStatus.FINISHED;
    }
}
