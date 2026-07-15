package com.home.batch.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class BatchExecutionCorrelationAdvisoryLockTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("같은 requestId의 advisory lock은 첫 Batch process가 끝날 때까지 두 번째 process를 직렬화한다")
    void serializesConcurrentUseOfSameRequestId() throws Exception {
        String requestId = "123e4567-e89b-12d3-a456-426614174040";
        BatchExecutionCorrelationGuard firstGuard = new BatchExecutionCorrelationGuard(dataSource);
        BatchExecutionCorrelationGuard secondGuard = new BatchExecutionCorrelationGuard(dataSource);
        BatchExecutionCorrelationGuard.Lock firstLock = firstGuard.lock(requestId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BatchExecutionCorrelationGuard.Lock> secondLock = executor.submit(() -> secondGuard.lock(requestId));
            Thread.sleep(200);
            assertThat(secondLock).isNotDone();

            firstLock.close();
            try (BatchExecutionCorrelationGuard.Lock acquired = secondLock.get(2, TimeUnit.SECONDS)) {
                assertThat(acquired).isNotNull();
            }
        } finally {
            firstLock.close();
            executor.shutdownNow();
        }
    }
}
