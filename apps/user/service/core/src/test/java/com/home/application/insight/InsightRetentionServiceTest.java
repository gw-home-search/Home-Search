package com.home.application.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.insight.port.InsightRetentionRepository;
import com.home.application.insight.port.InsightRetentionRepository.RetentionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsightRetentionServiceTest {

    @Test
    @DisplayName("retention 실행은 UTC 기준시각으로 만료된 inbox와 consumer evidence를 정리한다")
    void deletesExpiredRecordsAtClockInstant() {
        Instant now = Instant.parse("2026-07-25T03:00:00Z");
        RecordingRetentionRepository repository = new RecordingRetentionRepository();
        var service = new InsightRetentionService(repository, Clock.fixed(now, ZoneOffset.UTC));

        RetentionResult result = service.deleteExpired();

        assertThat(repository.cutoff).isEqualTo(now);
        assertThat(result).isEqualTo(new RetentionResult(2, 3));
    }

    private static final class RecordingRetentionRepository implements InsightRetentionRepository {
        private Instant cutoff;

        @Override
        public RetentionResult deleteExpired(Instant cutoff) {
            this.cutoff = cutoff;
            return new RetentionResult(2, 3);
        }
    }
}
