package com.home.batch.event;

import com.home.application.event.PropertyEventOutboxRelayService;
import java.time.Clock;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home.events.relay", name = "enabled", havingValue = "true")
class PropertyEventRelayJobConfiguration {

    @Bean
    @Lazy
    Job propertyEventRelayJob(JobRepository jobRepository, Step propertyEventRelayStep) {
        return new JobBuilder("propertyEventRelayJob", jobRepository)
                .start(propertyEventRelayStep)
                .build();
    }

    @Bean
    @Lazy
    Step propertyEventRelayStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            PropertyEventOutboxRelayService relayService,
            @Value("${home.events.relay.batch-size:100}") int batchSize,
            @Value("${home.events.relay.max-batches:1000}") int maxBatches) {
        DefaultTransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
        transactionAttribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder("propertyEventRelayStep", jobRepository)
                .tasklet(
                        new PropertyEventRelayTasklet(relayService, batchSize, maxBatches, Clock.systemUTC()),
                        transactionManager)
                .transactionAttribute(transactionAttribute)
                .build();
    }
}
