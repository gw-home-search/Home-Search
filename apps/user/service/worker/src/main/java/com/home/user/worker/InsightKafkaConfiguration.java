package com.home.user.worker;

import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Configuration
public class InsightKafkaConfiguration {

    @Bean
    Clock insightWorkerClock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultErrorHandler insightKafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations, (record, exception) -> new TopicPartition(record.topic() + ".dlq", -1));
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer, new SequentialKafkaBackOff());
    }
}
