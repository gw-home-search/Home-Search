package com.home.infrastructure.persistence.prediction;

import com.home.application.prediction.PredictionExecutionContext;
import com.home.application.prediction.PredictionProperties;
import com.home.infrastructure.external.prediction.PredictionRuntimeProperties;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PredictionRuntimeProperties.class)
class PredictionUseCaseConfiguration {

    @Bean
    PredictionProperties predictionProperties(PredictionRuntimeProperties properties) {
        PredictionRuntimeProperties.Cache cache = properties.cache();
        PredictionRuntimeProperties.Interval interval = properties.interval();
        return new PredictionProperties(
                properties.enabled(),
                properties.modelVersion(),
                cache.ttl(),
                cache.pendingTtl(),
                cache.failedTtl(),
                cache.unavailableTtl(),
                cache.lockTtl(),
                interval.pct(),
                interval.basis(),
                properties.zone());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService predictionExecutor(PredictionRuntimeProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(properties.executor().threads(), runnable -> {
            Thread thread = new Thread(runnable, "home-prediction-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    PredictionExecutionContext predictionExecutionContext(
            @Qualifier("predictionExecutor") ExecutorService predictionExecutor,
            PredictionRuntimeProperties properties) {
        return new PredictionExecutionContext(predictionExecutor, Clock.system(properties.zone()));
    }
}
