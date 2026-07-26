package com.home.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaPropertyEventPublisherTest {

    @Test
    @DisplayName("broker ack가 완료된 뒤 publish 호출을 반환한다")
    void waitsForBrokerAcknowledgement() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send("property.insight-events.v1", "snapshot-1", "{\"eventId\":\"event-1\"}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        new KafkaPropertyEventPublisher(kafkaTemplate, Duration.ofSeconds(1))
                .publish("property.insight-events.v1", "snapshot-1", "{\"eventId\":\"event-1\"}");

        verify(kafkaTemplate).send("property.insight-events.v1", "snapshot-1", "{\"eventId\":\"event-1\"}");
    }

    @Test
    @DisplayName("broker nack는 relay가 retry로 기록할 수 있는 비밀 비포함 예외로 변환한다")
    void convertsBrokerFailureWithoutLeakingDetails() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker-secret-detail"));
        when(kafkaTemplate.send("property.insight-events.v1", "snapshot-1", "{}"))
                .thenReturn(failed);

        assertThatThrownBy(() -> new KafkaPropertyEventPublisher(kafkaTemplate, Duration.ofSeconds(1))
                        .publish("property.insight-events.v1", "snapshot-1", "{}"))
                .isInstanceOf(PropertyEventPublishException.class)
                .hasMessage("Kafka publish was not acknowledged")
                .hasMessageNotContaining("broker-secret-detail");
    }

    @Test
    @DisplayName("256KiB를 넘는 전체 envelope는 broker 호출 전에 거부한다")
    void rejectsOversizedEnvelopeBeforeBrokerCall() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        String oversizedEnvelope = "가".repeat(100_000);

        assertThatThrownBy(() -> new KafkaPropertyEventPublisher(kafkaTemplate, Duration.ofSeconds(1))
                        .publish("property.insight-events.v1", "snapshot-1", oversizedEnvelope))
                .isInstanceOf(PropertyEventPublishException.class)
                .hasMessage("Kafka envelope exceeds 256KiB");
        verifyNoInteractions(kafkaTemplate);
    }
}
