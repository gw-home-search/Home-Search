package com.home.batch.news;

import com.home.application.news.collection.MarketNewsCollectionService;
import com.home.application.news.quality.MarketNewsQualitySamplingService;
import com.home.application.news.quality.MarketNewsQualityService;
import com.home.application.news.retention.MarketNewsRetentionService;
import com.home.application.news.selection.MajorNewsComplexSelectionService;
import com.home.infrastructure.external.news.NaverNewsProperties;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
class MarketNewsBatchJobConfiguration {

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Job marketNewsGeneralJob(JobRepository repository, Step marketNewsGeneralStep) {
        return new JobBuilder("marketNewsGeneralJob", repository)
                .start(marketNewsGeneralStep)
                .build();
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Job marketNewsMajorComplexJob(JobRepository repository, Step marketNewsMajorComplexStep) {
        return new JobBuilder("marketNewsMajorComplexJob", repository)
                .start(marketNewsMajorComplexStep)
                .build();
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Job marketNewsMorningJob(
            JobRepository repository, Step marketNewsGeneralStep, Step marketNewsMorningMajorComplexStep) {
        return new JobBuilder("marketNewsMorningJob", repository)
                .start(marketNewsGeneralStep)
                .next(marketNewsMorningMajorComplexStep)
                .build();
    }

    @Bean
    @Lazy
    Job marketNewsMajorSelectionJob(JobRepository repository, Step marketNewsMajorSelectionStep) {
        return new JobBuilder("marketNewsMajorSelectionJob", repository)
                .start(marketNewsMajorSelectionStep)
                .build();
    }

    @Bean
    @Lazy
    Job marketNewsRetentionJob(JobRepository repository, Step marketNewsRetentionStep) {
        return new JobBuilder("marketNewsRetentionJob", repository)
                .start(marketNewsRetentionStep)
                .build();
    }

    @Bean
    @Lazy
    Job marketNewsWithdrawalJob(JobRepository repository, Step marketNewsWithdrawalStep) {
        return new JobBuilder("marketNewsWithdrawalJob", repository)
                .start(marketNewsWithdrawalStep)
                .build();
    }

    @Bean
    @Lazy
    Job marketNewsQualitySampleJob(JobRepository repository, Step marketNewsQualitySampleStep) {
        return new JobBuilder("marketNewsQualitySampleJob", repository)
                .start(marketNewsQualitySampleStep)
                .build();
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Step marketNewsGeneralStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MarketNewsCollectionService service,
            NaverNewsProperties properties) {
        return taskletStep(
                "marketNewsGeneralStep",
                repository,
                transactionManager,
                new MarketNewsCollectionTasklet(service, properties.dailyCallBudget(), false));
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Step marketNewsMajorComplexStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MarketNewsCollectionService service,
            NaverNewsProperties properties) {
        return taskletStep(
                "marketNewsMajorComplexStep",
                repository,
                transactionManager,
                new MarketNewsCollectionTasklet(service, properties.dailyCallBudget(), true));
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "home.news.naver", name = "enabled", havingValue = "true")
    Step marketNewsMorningMajorComplexStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MarketNewsCollectionService service,
            NaverNewsProperties properties) {
        return taskletStep(
                "marketNewsMorningMajorComplexStep",
                repository,
                transactionManager,
                new MarketNewsCollectionTasklet(service, properties.dailyCallBudget(), true, true));
    }

    @Bean
    @Lazy
    Step marketNewsMajorSelectionStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MajorNewsComplexSelectionService service) {
        return taskletStep(
                "marketNewsMajorSelectionStep",
                repository,
                transactionManager,
                new MarketNewsMajorSelectionTasklet(service));
    }

    @Bean
    @Lazy
    Step marketNewsRetentionStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MarketNewsRetentionService service) {
        return taskletStep(
                "marketNewsRetentionStep", repository, transactionManager, new MarketNewsRetentionTasklet(service));
    }

    @Bean
    @Lazy
    Step marketNewsWithdrawalStep(
            JobRepository repository, PlatformTransactionManager transactionManager, MarketNewsQualityService service) {
        return taskletStep(
                "marketNewsWithdrawalStep", repository, transactionManager, new MarketNewsWithdrawalTasklet(service));
    }

    @Bean
    @Lazy
    Step marketNewsQualitySampleStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            MarketNewsQualitySamplingService service) {
        return taskletStep(
                "marketNewsQualitySampleStep",
                repository,
                transactionManager,
                new MarketNewsQualitySampleTasklet(service));
    }

    private Step taskletStep(
            String name,
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            org.springframework.batch.core.step.tasklet.Tasklet tasklet) {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder(name, repository)
                .tasklet(tasklet, transactionManager)
                .transactionAttribute(attribute)
                .build();
    }
}
