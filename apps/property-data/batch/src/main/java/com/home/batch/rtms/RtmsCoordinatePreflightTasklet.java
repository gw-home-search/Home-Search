package com.home.batch.rtms;

import com.home.application.ingest.rtms.RtmsCoordinateSourcePreflight;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class RtmsCoordinatePreflightTasklet implements Tasklet {

    private final RtmsCoordinateSourcePreflight preflight;

    public RtmsCoordinatePreflightTasklet(RtmsCoordinateSourcePreflight preflight) {
        this.preflight = preflight;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        preflight.verify();
        return RepeatStatus.FINISHED;
    }
}
