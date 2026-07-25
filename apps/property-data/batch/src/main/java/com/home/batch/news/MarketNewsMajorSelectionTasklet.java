package com.home.batch.news;

import com.home.application.news.selection.MajorNewsComplexSelectionService;
import java.time.LocalDate;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketNewsMajorSelectionTasklet implements Tasklet {

    private final MajorNewsComplexSelectionService service;

    MarketNewsMajorSelectionTasklet(MajorNewsComplexSelectionService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String runDate = contribution.getStepExecution().getJobParameters().getString("runDate");
        service.select(LocalDate.parse(runDate));
        return RepeatStatus.FINISHED;
    }
}
