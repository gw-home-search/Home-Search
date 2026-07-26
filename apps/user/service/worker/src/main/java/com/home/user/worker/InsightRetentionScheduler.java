package com.home.user.worker;

import com.home.application.insight.InsightRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "home.insight.retention", name = "enabled", havingValue = "true")
public class InsightRetentionScheduler {

    private final InsightRetentionService service;

    public InsightRetentionScheduler(InsightRetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${home.insight.retention-cron:0 45 4 * * *}", zone = "Asia/Seoul")
    public void deleteExpired() {
        service.deleteExpired();
    }
}
