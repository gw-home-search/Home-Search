package com.home.application.insight;

import com.home.application.insight.port.InsightRetentionRepository;
import com.home.application.insight.port.InsightRetentionRepository.RetentionResult;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsightRetentionService {

    private final InsightRetentionRepository repository;
    private final Clock clock;

    public InsightRetentionService(InsightRetentionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public RetentionResult deleteExpired() {
        return repository.deleteExpired(clock.instant());
    }
}
