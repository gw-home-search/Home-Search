package com.home.infrastructure.event;

import com.home.application.event.PropertyEventOutboxRelayService;
import com.home.application.event.PropertyEventOutboxRepository;
import com.home.application.event.PropertyEventOutboxRetentionService;
import com.home.application.event.PropertyEventPublisher;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
public class PropertyEventRelayConfiguration {

    @Bean
    PropertyEventOutboxRepository propertyEventOutboxRepository(JdbcClient jdbcClient) {
        return new JdbcPropertyEventOutboxRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.events.relay", name = "enabled", havingValue = "true")
    PropertyEventPublisher propertyEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${home.events.relay.publish-timeout-millis:10000}") long publishTimeoutMillis) {
        return new KafkaPropertyEventPublisher(kafkaTemplate, Duration.ofMillis(publishTimeoutMillis));
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.events.relay", name = "enabled", havingValue = "true")
    PropertyEventOutboxRelayService propertyEventOutboxRelayService(
            PropertyEventOutboxRepository repository, PropertyEventPublisher publisher) {
        return new PropertyEventOutboxRelayService(repository, publisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.events.retention", name = "enabled", havingValue = "true")
    PropertyEventOutboxRetentionService propertyEventOutboxRetentionService(PropertyEventOutboxRepository repository) {
        return new PropertyEventOutboxRetentionService(repository);
    }
}
