package com.home.infrastructure.event;

import com.home.application.event.PropertyEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

final class KafkaPropertyEventPublisher implements PropertyEventPublisher {

    private static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration publishTimeout;

    KafkaPropertyEventPublisher(KafkaTemplate<String, String> kafkaTemplate, Duration publishTimeout) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate);
        this.publishTimeout = Objects.requireNonNull(publishTimeout);
        if (publishTimeout.isZero() || publishTimeout.isNegative()) {
            throw new IllegalArgumentException("publishTimeout must be positive");
        }
    }

    @Override
    public void publish(String topicName, String messageKey, String envelopeJson) {
        if (envelopeJson.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new PropertyEventPublishException("Kafka envelope exceeds 256KiB", null);
        }
        try {
            kafkaTemplate
                    .send(topicName, messageKey, envelopeJson)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PropertyEventPublishException("Kafka publish interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new PropertyEventPublishException("Kafka publish was not acknowledged", exception);
        }
    }
}
