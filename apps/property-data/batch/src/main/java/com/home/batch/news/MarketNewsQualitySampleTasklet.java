package com.home.batch.news;

import com.home.application.news.quality.MarketNewsQualitySamplingService;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketNewsQualitySampleTasklet implements Tasklet {

    private final MarketNewsQualitySamplingService service;

    MarketNewsQualitySampleTasklet(MarketNewsQualitySamplingService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var parameters = contribution.getStepExecution().getJobParameters();
        service.sample(UUID.fromString(parameters.getString("reviewSetId")), parameters.getString("policyVersion"));
        return RepeatStatus.FINISHED;
    }
}
