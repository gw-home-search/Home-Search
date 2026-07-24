package com.home.batch.insight;

import com.home.application.insight.generation.MarketInsightWeeklyBuildService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketInsightWeeklyTasklet implements Tasklet {

    private final MarketInsightWeeklyBuildService buildService;

    MarketInsightWeeklyTasklet(MarketInsightWeeklyBuildService buildService) {
        this.buildService = buildService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate runDate = LocalDate.parse(
                contribution.getStepExecution().getJobParameters().getString("runDate"));
        LocalDate weekStart =
                runDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        buildService.build(weekStart);
        return RepeatStatus.FINISHED;
    }
}
