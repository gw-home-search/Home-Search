package com.home.batch.rtms;

import com.home.application.ingest.rtms.RtmsCoordinateSourcePreflight;
import com.home.application.ingest.rtms.RtmsMonthlyRefreshUseCase;
import com.home.application.insight.collection.RtmsCollectionExecutionTracker;
import com.home.application.region.RegionSiGunGuCodeReader;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.external.rtms.RtmsIngestProperties;
import com.home.infrastructure.ops.notification.OpsNotifier;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RtmsIngestProperties.class)
class RtmsBatchJobConfiguration {

    @Bean
    RtmsRefreshWorksetPlanner rtmsRefreshWorksetPlanner(RegionSiGunGuCodeReader lawdCodeReader) {
        return new RtmsRefreshWorksetPlanner(lawdCodeReader);
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
            @Qualifier("marketInsightDailyStep") ObjectProvider<Step> marketInsightDailyStepProvider,
            @Qualifier("marketInsightRolling7dStep") ObjectProvider<Step> marketInsightRolling7dStepProvider,
            RtmsBatchSummaryListener listener) {
        Step marketInsightDailyStep = marketInsightDailyStepProvider.getIfAvailable();
        Step marketInsightRolling7dStep = marketInsightRolling7dStepProvider.getIfAvailable();
        if ((marketInsightDailyStep == null) != (marketInsightRolling7dStep == null)) {
            throw new IllegalStateException("daily and rolling insight steps must be configured together");
        }
        SimpleJobBuilder builder = new JobBuilder("rtmsDailyRefreshJob", jobRepository)
                .start(coordinatePreflightStep)
                .next(rtmsDailyMonthlyIngestStep)
                .next(regionUnitSyncStep);
        if (marketInsightDailyStep != null) {
            builder.next(marketInsightDailyStep).next(marketInsightRolling7dStep);
        }
        return builder.listener(listener).build();
    }

    @Bean
    @Lazy
    Job rtmsBackfillJob(
            JobRepository jobRepository, Step rtmsBackfillMonthlyIngestStep, RtmsBatchSummaryListener listener) {
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
            RtmsCoordinateSourcePreflight preflight) {
        return taskletStep(
                "coordinatePreflightStep",
                jobRepository,
                transactionManager,
                new RtmsCoordinatePreflightTasklet(preflight));
    }

    @Bean
    @Lazy
    Step rtmsDailyMonthlyIngestStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RtmsMonthlyRefreshUseCase useCase,
            RtmsRefreshWorksetPlanner planner,
            RtmsCollectionExecutionTracker collectionTracker,
            RtmsIngestProperties properties) {
        return taskletStep(
                "monthlyIngestStep",
                jobRepository,
                transactionManager,
                new RtmsMonthlyRefreshTasklet(
                        useCase,
                        planner,
                        properties.daily().lawdCds(),
                        properties.daily().lookbackMonths(),
                        true,
                        collectionTracker));
    }

    @Bean
    @Lazy
    Step rtmsBackfillMonthlyIngestStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RtmsMonthlyRefreshUseCase useCase,
            RtmsRefreshWorksetPlanner planner,
            RtmsCollectionExecutionTracker collectionTracker) {
        return taskletStep(
                "monthlyIngestStep",
                jobRepository,
                transactionManager,
                new RtmsMonthlyRefreshTasklet(useCase, planner, "", 0, false, collectionTracker));
    }

    @Bean
    @Lazy
    Step regionUnitSyncStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RegionUnitCntSynchronizationService synchronizationService) {
        return taskletStep(
                "regionUnitSyncStep",
                jobRepository,
                transactionManager,
                new RtmsRegionUnitSyncTasklet(synchronizationService));
    }

    private static Step taskletStep(
            String name,
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            org.springframework.batch.core.step.tasklet.Tasklet tasklet) {
        DefaultTransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
        transactionAttribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder(name, jobRepository)
                .tasklet(tasklet, transactionManager)
                .transactionAttribute(transactionAttribute)
                .build();
    }
}
