package com.home.batch.event;

import com.home.application.event.PropertyEventOutboxRetentionService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home.events.retention", name = "enabled", havingValue = "true")
class PropertyEventOutboxRetentionJobConfiguration {

    @Bean
    @Lazy
    Job propertyEventOutboxRetentionJob(JobRepository jobRepository, Step propertyEventOutboxRetentionStep) {
        return new JobBuilder("propertyEventOutboxRetentionJob", jobRepository)
                .start(propertyEventOutboxRetentionStep)
                .build();
    }

    @Bean
    @Lazy
    Step propertyEventOutboxRetentionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            PropertyEventOutboxRetentionService service,
            @Value("${home.events.retention.days:30}") long retentionDays,
            @Value("${home.events.retention.batch-size:500}") int batchSize,
            @Value("${home.events.retention.max-batches:20}") int maxBatches) {
        return new StepBuilder("propertyEventOutboxRetentionStep", jobRepository)
                .tasklet(
                        new PropertyEventOutboxRetentionTasklet(
                                service, Duration.ofDays(retentionDays), batchSize, maxBatches, Clock.systemUTC()),
                        transactionManager)
                .build();
    }
}
