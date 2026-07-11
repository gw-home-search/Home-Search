package com.home.batch.rtms;

import com.home.application.ingest.rtms.RtmsCoordinateSourcePreflight;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.region.RegionSiGunGuCodeReader;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.ops.notification.OpsNotifier;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
class RtmsBatchJobConfiguration {

	@Bean
	RtmsRefreshWorksetPlanner rtmsRefreshWorksetPlanner(ObjectProvider<RegionSiGunGuCodeReader> lawdCodeReader) {
		return new RtmsRefreshWorksetPlanner(lawdCodeReader.getIfAvailable(RegionSiGunGuCodeReader::empty));
	}

	@Bean
	RtmsBatchSummaryListener rtmsBatchSummaryListener(OpsNotifier notifier) {
		return new RtmsBatchSummaryListener(notifier);
	}

	@Bean
	@Lazy
	Job rtmsDailyRefreshJob(
		JobRepository jobRepository,
		Step coordinatePreflightStep,
		Step rtmsDailyMonthlyIngestStep,
		Step regionUnitSyncStep,
		RtmsBatchSummaryListener listener
	) {
		return new JobBuilder("rtmsDailyRefreshJob", jobRepository)
			.start(coordinatePreflightStep)
			.next(rtmsDailyMonthlyIngestStep)
			.next(regionUnitSyncStep)
			.listener(listener)
			.build();
	}

	@Bean
	@Lazy
	Job rtmsBackfillJob(
		JobRepository jobRepository,
		Step rtmsBackfillMonthlyIngestStep,
		RtmsBatchSummaryListener listener
	) {
		return new JobBuilder("rtmsBackfillJob", jobRepository)
			.start(rtmsBackfillMonthlyIngestStep)
			.listener(listener)
			.build();
	}

	@Bean
	@Lazy
	Step coordinatePreflightStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		RtmsCoordinateSourcePreflight preflight
	) {
		return taskletStep(
			"coordinatePreflightStep",
			jobRepository,
			transactionManager,
			new RtmsCoordinatePreflightTasklet(preflight)
		);
	}

	@Bean
	@Lazy
	Step rtmsDailyMonthlyIngestStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		RtmsMonthlyRefreshUseCase useCase,
		RtmsRefreshWorksetPlanner planner,
		@Value("${home.ingest.rtms.daily.lawd-cds:}") String lawdCds,
		@Value("${home.ingest.rtms.daily.lookback-months:1}") int lookbackMonths
	) {
		return taskletStep(
			"monthlyIngestStep",
			jobRepository,
			transactionManager,
			new RtmsMonthlyRefreshTasklet(useCase, planner, lawdCds, lookbackMonths, true)
		);
	}

	@Bean
	@Lazy
	Step rtmsBackfillMonthlyIngestStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		RtmsMonthlyRefreshUseCase useCase,
		RtmsRefreshWorksetPlanner planner
	) {
		return taskletStep(
			"monthlyIngestStep",
			jobRepository,
			transactionManager,
			new RtmsMonthlyRefreshTasklet(useCase, planner, "", 0, false)
		);
	}

	@Bean
	@Lazy
	Step regionUnitSyncStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		ObjectProvider<RegionUnitCntSynchronizationService> synchronizationService
	) {
		return taskletStep(
			"regionUnitSyncStep",
			jobRepository,
			transactionManager,
			new RtmsRegionUnitSyncTasklet(synchronizationService.getIfAvailable())
		);
	}

	private static Step taskletStep(
		String name,
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		org.springframework.batch.core.step.tasklet.Tasklet tasklet
	) {
		DefaultTransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
		transactionAttribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
		return new StepBuilder(name, jobRepository)
			.tasklet(tasklet, transactionManager)
			.transactionAttribute(transactionAttribute)
			.build();
	}
}
