package com.home.batch.news;

import com.home.application.news.retention.MarketNewsRetentionService;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketNewsRetentionTasklet implements Tasklet {

    private final MarketNewsRetentionService service;

    MarketNewsRetentionTasklet(MarketNewsRetentionService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        service.run();
        return RepeatStatus.FINISHED;
    }
}
