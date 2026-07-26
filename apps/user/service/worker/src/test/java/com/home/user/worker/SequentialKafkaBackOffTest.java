package com.home.user.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class SequentialKafkaBackOffTest {

    @Test
    @DisplayName("consumer retry는 1초, 5초, 30초 뒤 DLQ로 전환한다")
    void retriesWithDocumentedDelays() {
        var execution = new SequentialKafkaBackOff().start();

        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(5_000);
        assertThat(execution.nextBackOff()).isEqualTo(30_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
