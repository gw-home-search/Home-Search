package com.home.batch.launch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class BatchExecutionCorrelationGuard {

    private final JdbcClient jdbcClient;
    private final DataSource dataSource;

    @Autowired
    public BatchExecutionCorrelationGuard(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    BatchExecutionCorrelationGuard(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        this.dataSource = null;
    }

    Lock lock(String requestId) {
        if (dataSource == null) {
            return () -> {};
        }
        try {
            Connection connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
                statement.setString(1, requestId);
                try (ResultSet ignored = statement.executeQuery()) {
                    ignored.next();
                }
            }
            return () -> unlockAndClose(connection, requestId);
        } catch (SQLException exception) {
            throw new BatchExitCodeException(
                    "Failed to acquire Batch execution correlation lock",
                    BatchExitCodeExceptionMapper.FAILED_JOB_EXIT_CODE);
        }
    }

    public void verify(String jobName, JobParameters parameters) {
        String requestId = parameters.getString("requestId");
        Map<String, String> requested = identifyingParameters(parameters);
        List<ExistingInstance> instances = jdbcClient
                .sql("""
			SELECT DISTINCT instance.JOB_INSTANCE_ID, instance.JOB_NAME
			FROM batch.BATCH_JOB_EXECUTION_PARAMS request_param
			JOIN batch.BATCH_JOB_EXECUTION execution
			  ON execution.JOB_EXECUTION_ID = request_param.JOB_EXECUTION_ID
			JOIN batch.BATCH_JOB_INSTANCE instance
			  ON instance.JOB_INSTANCE_ID = execution.JOB_INSTANCE_ID
			WHERE request_param.PARAMETER_NAME = 'requestId'
			  AND request_param.PARAMETER_VALUE = :requestId
			  AND request_param.IDENTIFYING = 'Y'
			""")
                .param("requestId", requestId)
                .query((resultSet, rowNumber) ->
                        new ExistingInstance(resultSet.getLong("JOB_INSTANCE_ID"), resultSet.getString("JOB_NAME")))
                .list();

        for (ExistingInstance instance : instances) {
            Map<String, String> existing = identifyingParameters(instance.id());
            if (!instance.jobName().equals(jobName) || !existing.equals(requested)) {
                throw new BatchExitCodeException(
                        "requestId was already used by a different Batch parameter set",
                        BatchExitCodeExceptionMapper.INVALID_ARGUMENT_EXIT_CODE);
            }
        }
    }

    private Map<String, String> identifyingParameters(JobParameters parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.getParameters().entrySet().stream()
                .filter(entry -> entry.getValue().isIdentifying())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.put(entry.getKey(), stringValue(entry.getValue())));
        return values;
    }

    private Map<String, String> identifyingParameters(long jobInstanceId) {
        Long executionId = jdbcClient
                .sql("""
			SELECT max(JOB_EXECUTION_ID)
			FROM batch.BATCH_JOB_EXECUTION
			WHERE JOB_INSTANCE_ID = :jobInstanceId
			""")
                .param("jobInstanceId", jobInstanceId)
                .query(Long.class)
                .single();
        Map<String, String> values = new LinkedHashMap<>();
        jdbcClient
                .sql("""
			SELECT PARAMETER_NAME, PARAMETER_VALUE
			FROM batch.BATCH_JOB_EXECUTION_PARAMS
			WHERE JOB_EXECUTION_ID = :executionId
			  AND IDENTIFYING = 'Y'
			ORDER BY PARAMETER_NAME
			""")
                .param("executionId", executionId)
                .query((resultSet, rowNumber) ->
                        Map.entry(resultSet.getString("PARAMETER_NAME"), resultSet.getString("PARAMETER_VALUE")))
                .list()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return values;
    }

    private String stringValue(JobParameter<?> parameter) {
        return parameter.getValue() == null ? null : parameter.getValue().toString();
    }

    private void unlockAndClose(Connection connection, String requestId) {
        try (connection;
                PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, requestId);
            statement.executeQuery().close();
        } catch (SQLException ignored) {
            // Closing the PostgreSQL session releases any remaining session advisory lock.
        }
    }

    @FunctionalInterface
    interface Lock extends AutoCloseable {

        @Override
        void close();
    }

    private record ExistingInstance(long id, String jobName) {}
}
