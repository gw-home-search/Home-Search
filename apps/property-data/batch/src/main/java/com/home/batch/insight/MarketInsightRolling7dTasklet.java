package com.home.batch.insight;

import com.home.application.insight.generation.MarketInsightBuildResult;
import com.home.application.insight.generation.MarketInsightRolling7dBuildService;
import java.time.LocalDate;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

final class MarketInsightRolling7dTasklet implements Tasklet {

    private final MarketInsightRolling7dBuildService buildService;

    MarketInsightRolling7dTasklet(MarketInsightRolling7dBuildService buildService) {
        this.buildService = buildService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String runDate = contribution.getStepExecution().getJobParameters().getString("runDate");
        MarketInsightBuildResult result = buildService.build(LocalDate.parse(runDate));
        if (!result.published()) {
            throw new IllegalStateException("rolling market insight 발행 거부: " + result.rejectionReason());
        }
        return RepeatStatus.FINISHED;
    }
}
