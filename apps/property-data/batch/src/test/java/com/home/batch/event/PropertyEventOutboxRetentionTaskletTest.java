package com.home.batch.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.home.application.event.PropertyEventOutboxRetentionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class PropertyEventOutboxRetentionTaskletTest {

    @Test
    @DisplayName("UTC 기준 30일 retention과 bounded delete 설정으로 maintenance를 실행한다")
    void runsBoundedRetentionMaintenance() throws Exception {
        Instant now = Instant.parse("2026-07-25T03:00:00Z");
        PropertyEventOutboxRetentionService service = mock(PropertyEventOutboxRetentionService.class);

        RepeatStatus status = new PropertyEventOutboxRetentionTasklet(
                        service, Duration.ofDays(30), 500, 20, Clock.fixed(now, ZoneOffset.UTC))
                .execute(null, null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(service).deleteExpired(now, Duration.ofDays(30), 500, 20);
    }
}
