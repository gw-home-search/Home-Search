package com.home.batch.insight;

import com.home.application.insight.generation.MarketInsightDailyBuildService;
import java.time.LocalDate;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketInsightDailyTasklet implements Tasklet {

    private final MarketInsightDailyBuildService buildService;

    MarketInsightDailyTasklet(MarketInsightDailyBuildService buildService) {
        this.buildService = buildService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String runDate = contribution.getStepExecution().getJobParameters().getString("runDate");
        buildService.build(LocalDate.parse(runDate));
        return RepeatStatus.FINISHED;
    }
}
