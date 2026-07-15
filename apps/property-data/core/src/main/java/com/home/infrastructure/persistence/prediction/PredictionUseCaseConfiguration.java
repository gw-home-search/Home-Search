package com.home.infrastructure.persistence.prediction;

import com.home.application.prediction.PredictionExecutionContext;
import com.home.application.prediction.PredictionProperties;
import com.home.infrastructure.external.prediction.PredictionRuntimeProperties;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    @Bean
    ThreadPoolTaskExecutor predictionExecutor(PredictionRuntimeProperties properties) {
        PredictionRuntimeProperties.Executor executor = properties.executor();
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(executor.threads());
        taskExecutor.setMaxPoolSize(executor.threads());
        taskExecutor.setQueueCapacity(executor.queueCapacity());
        taskExecutor.setThreadNamePrefix("home-prediction-");
        taskExecutor.setDaemon(true);
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationMillis(executor.shutdownAwait().toMillis());
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return taskExecutor;
    }

    @Bean
    PredictionExecutionContext predictionExecutionContext(
            @Qualifier("predictionExecutor") Executor predictionExecutor, PredictionRuntimeProperties properties) {
        return new PredictionExecutionContext(predictionExecutor, Clock.system(properties.zone()));
    }
}
