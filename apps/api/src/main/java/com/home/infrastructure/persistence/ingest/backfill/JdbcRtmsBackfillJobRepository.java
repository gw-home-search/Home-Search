package com.home.infrastructure.persistence.ingest.backfill;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.home.application.ingest.backfill.RtmsBackfillJobRecord;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;
import com.home.domain.ingest.backfill.RtmsBackfillJobStatus;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRtmsBackfillJobRepository implements RtmsBackfillJobRepository {

	private final JdbcClient jdbcClient;
	private final Clock clock;

	public JdbcRtmsBackfillJobRepository(JdbcClient jdbcClient, Clock clock) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public RtmsBackfillJobRecord createIfAbsent(
		String jobKey,
		String source,
		String dealYmdFrom,
		String dealYmdTo,
		String lawdCodeSource,
		int totalChunkCount
	) {
		jdbcClient.sql("""
			INSERT INTO rtms_backfill_job (
			    job_key,
			    source,
			    deal_ymd_from,
			    deal_ymd_to,
			    lawd_code_source,
			    status,
			    total_chunk_count
			)
			VALUES (
			    :jobKey,
			    :source,
			    :dealYmdFrom,
			    :dealYmdTo,
			    :lawdCodeSource,
			    'PLANNED',
			    :totalChunkCount
			)
			ON CONFLICT (job_key) DO NOTHING
			""")
			.param("jobKey", jobKey)
			.param("source", source)
			.param("dealYmdFrom", dealYmdFrom)
			.param("dealYmdTo", dealYmdTo)
			.param("lawdCodeSource", lawdCodeSource)
			.param("totalChunkCount", totalChunkCount)
			.update();
		return jdbcClient.sql("""
			SELECT *
			FROM rtms_backfill_job
			WHERE job_key = :jobKey
			""")
			.param("jobKey", jobKey)
			.query(this::mapRecord)
			.single();
	}

	@Override
	public void markRunning(long jobId) {
		jdbcClient.sql("""
			UPDATE rtms_backfill_job
			SET status = 'RUNNING',
			    started_at = COALESCE(started_at, :now),
			    completed_at = NULL,
			    failure_reason = NULL
			WHERE id = :jobId
			""")
			.param("jobId", jobId)
			.param("now", offset(clock.instant()))
			.update();
	}

	@Override
	public void markCompleted(long jobId) {
		jdbcClient.sql("""
			UPDATE rtms_backfill_job
			SET status = 'COMPLETED',
			    completed_at = :now,
			    failure_reason = NULL
			WHERE id = :jobId
			""")
			.param("jobId", jobId)
			.param("now", offset(clock.instant()))
			.update();
	}

	@Override
	public void markPartial(long jobId, String failureReason) {
		jdbcClient.sql("""
			UPDATE rtms_backfill_job
			SET status = 'PARTIAL',
			    completed_at = :now,
			    failure_reason = :failureReason
			WHERE id = :jobId
			""")
			.param("jobId", jobId)
			.param("failureReason", failureReason)
			.param("now", offset(clock.instant()))
			.update();
	}

	private RtmsBackfillJobRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RtmsBackfillJobRecord(
			resultSet.getLong("id"),
			resultSet.getString("job_key"),
			resultSet.getString("source"),
			resultSet.getString("deal_ymd_from"),
			resultSet.getString("deal_ymd_to"),
			resultSet.getString("lawd_code_source"),
			RtmsBackfillJobStatus.valueOf(resultSet.getString("status"))
		);
	}

	private OffsetDateTime offset(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
