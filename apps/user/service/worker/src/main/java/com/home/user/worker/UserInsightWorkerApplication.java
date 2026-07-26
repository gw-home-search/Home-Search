package com.home.user.worker;

import com.home.application.insight.InsightPublishedEventService;
import com.home.application.insight.InsightRetentionService;
import com.home.infrastructure.persistence.user.JdbcInsightEventRepository;
import com.home.infrastructure.persistence.user.JdbcInsightRetentionRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableKafka
@EnableScheduling
@Import({
    InsightPublishedEventService.class,
    InsightRetentionService.class,
    JdbcInsightEventRepository.class,
    JdbcInsightRetentionRepository.class
})
@SpringBootApplication(scanBasePackageClasses = UserInsightWorkerApplication.class)
public class UserInsightWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserInsightWorkerApplication.class, args);
    }
}
