package com.home.batch.launch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BatchExecutionCorrelationGuardTest {

    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174010";

    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:correlation-guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("CREATE SCHEMA batch").update();
        jdbcClient.sql("""
			CREATE TABLE batch.BATCH_JOB_INSTANCE (
			  JOB_INSTANCE_ID BIGINT PRIMARY KEY, JOB_NAME VARCHAR(100) NOT NULL
			)
			""").update();
        jdbcClient.sql("""
			CREATE TABLE batch.BATCH_JOB_EXECUTION (
			  JOB_EXECUTION_ID BIGINT PRIMARY KEY, JOB_INSTANCE_ID BIGINT NOT NULL
			)
			""").update();
        jdbcClient.sql("""
			CREATE TABLE batch.BATCH_JOB_EXECUTION_PARAMS (
			  JOB_EXECUTION_ID BIGINT NOT NULL, PARAMETER_NAME VARCHAR(100) NOT NULL,
			  PARAMETER_VALUE VARCHAR(2500), IDENTIFYING CHAR(1) NOT NULL
			)
			""").update();
        seedExecution(1, 11, "rtmsDailyRefreshJob", "2026-07-10", REQUEST_ID);
    }

    @Test
    @DisplayName("동일 job parameter set의 UUID 재사용은 같은 JobInstance restart로 허용한다")
    void allowsSameJobInstanceRestart() {
        BatchExecutionCorrelationGuard guard = new BatchExecutionCorrelationGuard(jdbcClient);

        assertThatCode(() -> guard.verify("rtmsDailyRefreshJob", parameters("2026-07-10", REQUEST_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 UUID를 다른 parameter set 또는 job에서 재사용하면 exit 2로 거부한다")
    void rejectsReuseAcrossDifferentParameterSetOrJob() {
        BatchExecutionCorrelationGuard guard = new BatchExecutionCorrelationGuard(jdbcClient);

        assertThatThrownBy(() -> guard.verify("rtmsDailyRefreshJob", parameters("2026-07-11", REQUEST_ID)))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
        assertThatThrownBy(() -> guard.verify(
                        "rtmsBackfillJob",
                        new JobParameters(Set.of(
                                new JobParameter<>("fromYmd", "202607", String.class, true),
                                new JobParameter<>("toYmd", "202607", String.class, true),
                                new JobParameter<>("lawdCds", "11680", String.class, true),
                                new JobParameter<>("requestId", REQUEST_ID, String.class, true)))))
                .isInstanceOf(BatchExitCodeException.class)
                .extracting("exitCode")
                .isEqualTo(2);
    }

    private JobParameters parameters(String runDate, String requestId) {
        return new JobParameters(Set.of(
                new JobParameter<>("runDate", runDate, String.class, true),
                new JobParameter<>("requestId", requestId, String.class, true)));
    }

    private void seedExecution(long instanceId, long executionId, String jobName, String runDate, String requestId) {
        jdbcClient
                .sql("INSERT INTO batch.BATCH_JOB_INSTANCE VALUES (:instanceId, :jobName)")
                .param("instanceId", instanceId)
                .param("jobName", jobName)
                .update();
        jdbcClient
                .sql("INSERT INTO batch.BATCH_JOB_EXECUTION VALUES (:executionId, :instanceId)")
                .param("executionId", executionId)
                .param("instanceId", instanceId)
                .update();
        jdbcClient
                .sql("""
			INSERT INTO batch.BATCH_JOB_EXECUTION_PARAMS VALUES
			(:executionId, 'runDate', :runDate, 'Y'),
			(:executionId, 'requestId', :requestId, 'Y')
			""")
                .param("executionId", executionId)
                .param("runDate", runDate)
                .param("requestId", requestId)
                .update();
    }
}
