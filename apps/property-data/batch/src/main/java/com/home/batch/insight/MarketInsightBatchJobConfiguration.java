package com.home.batch.insight;

import com.home.application.insight.generation.MarketInsightDailyBuildService;
import com.home.application.insight.generation.MarketInsightRolling7dBuildService;
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
@ConditionalOnProperty(prefix = "home.insight.trade", name = "enabled", havingValue = "true")
class MarketInsightBatchJobConfiguration {

    @Bean
    @Lazy
    Job marketInsightDailyJob(JobRepository jobRepository, Step marketInsightDailyStep) {
        return new JobBuilder("marketInsightDailyJob", jobRepository)
                .start(marketInsightDailyStep)
                .build();
    }

    @Bean
    @Lazy
    Job marketInsightRolling7dJob(JobRepository jobRepository, Step marketInsightRolling7dStep) {
        return new JobBuilder("marketInsightRolling7dJob", jobRepository)
                .start(marketInsightRolling7dStep)
                .build();
    }

    @Bean
    @Lazy
    Step marketInsightDailyStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            MarketInsightDailyBuildService buildService) {
        DefaultTransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
        transactionAttribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder("marketInsightDailyStep", jobRepository)
                .tasklet(new MarketInsightDailyTasklet(buildService), transactionManager)
                .transactionAttribute(transactionAttribute)
                .build();
    }

    @Bean
    @Lazy
    Step marketInsightRolling7dStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            MarketInsightRolling7dBuildService buildService) {
        DefaultTransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
        transactionAttribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder("marketInsightRolling7dStep", jobRepository)
                .tasklet(new MarketInsightRolling7dTasklet(buildService), transactionManager)
                .transactionAttribute(transactionAttribute)
                .build();
    }
}
