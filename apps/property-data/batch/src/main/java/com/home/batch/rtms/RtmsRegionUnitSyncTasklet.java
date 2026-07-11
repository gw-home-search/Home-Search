package com.home.batch.rtms;

import com.home.application.region.RegionUnitCntSynchronizationService;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public class RtmsRegionUnitSyncTasklet implements Tasklet {

	private final RegionUnitCntSynchronizationService synchronizationService;

	public RtmsRegionUnitSyncTasklet(RegionUnitCntSynchronizationService synchronizationService) {
		this.synchronizationService = synchronizationService;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		if (synchronizationService == null) {
			return RepeatStatus.FINISHED;
		}
		try {
			synchronizationService.synchronize();
		}
		catch (RuntimeException exception) {
			chunkContext.getStepContext()
				.getStepExecution()
				.getJobExecution()
				.getExecutionContext()
				.put(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY, true);
			contribution.setExitStatus(RtmsMonthlyRefreshTasklet.COMPLETED_WITH_WARNINGS);
		}
		return RepeatStatus.FINISHED;
	}
}
