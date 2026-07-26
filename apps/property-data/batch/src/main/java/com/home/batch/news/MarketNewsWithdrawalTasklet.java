package com.home.batch.news;

import com.home.application.news.quality.MarketNewsQualityService;
import com.home.domain.news.MarketNewsWithdrawalReason;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketNewsWithdrawalTasklet implements Tasklet {

    private final MarketNewsQualityService service;

    MarketNewsWithdrawalTasklet(MarketNewsQualityService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var parameters = contribution.getStepExecution().getJobParameters();
        service.withdraw(
                UUID.fromString(parameters.getString("snapshotId")),
                MarketNewsWithdrawalReason.valueOf(parameters.getString("reason")));
        return RepeatStatus.FINISHED;
    }
}
