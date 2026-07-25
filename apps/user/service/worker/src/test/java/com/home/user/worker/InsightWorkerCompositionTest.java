package com.home.user.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.home.application.insight.InsightRetentionService;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;

class InsightWorkerCompositionTest {

    @Test
    @DisplayName("DLQ error handler와 UTC clock을 worker 구성에서 생성한다")
    void createsWorkerInfrastructureBeans() {
        var configuration = new InsightKafkaConfiguration();

        DefaultErrorHandler errorHandler = configuration.insightKafkaErrorHandler(mock(KafkaOperations.class));

        assertThat(errorHandler).isNotNull();
        assertThat(configuration.insightWorkerClock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("retention scheduler는 application transaction service만 호출한다")
    void delegatesScheduledRetention() {
        InsightRetentionService service = mock(InsightRetentionService.class);
        var scheduler = new InsightRetentionScheduler(service);

        scheduler.deleteExpired();

        verify(service).deleteExpired();
    }

    @Test
    @DisplayName("retention scheduler는 명시적으로 활성화한 단일 workload에서만 등록한다")
    void requiresExplicitRetentionOptIn() {
        ConditionalOnProperty condition = InsightRetentionScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("home.insight.retention");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    @DisplayName("worker application configuration은 독립적으로 생성 가능하다")
    void constructsApplicationConfiguration() {
        assertThat(new UserInsightWorkerApplication()).isNotNull();
    }
}
