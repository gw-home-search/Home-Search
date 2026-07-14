package com.home.batch.launch;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class BatchMetadataSchemaPreflight {

    private final JdbcClient jdbcClient;

    public BatchMetadataSchemaPreflight(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void verify() {
        String jobInstanceTable = jdbcClient
                .sql("SELECT to_regclass('batch.BATCH_JOB_INSTANCE')::text")
                .query(String.class)
                .optional()
                .orElse(null);
        if (!"batch.batch_job_instance".equals(jobInstanceTable)) {
            throw new BatchExitCodeException(
                    "Spring Batch metadata schema is missing: batch.BATCH_JOB_INSTANCE",
                    BatchExitCodeExceptionMapper.INVALID_ARGUMENT_EXIT_CODE);
        }
    }
}
