package com.home.infrastructure.persistence.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.backfill.RtmsBackfillChunkClaim;
import com.home.application.ingest.backfill.RtmsBackfillChunkRecord;
import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.application.ingest.backfill.RtmsBackfillChunkRequest;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatus;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRtmsBackfillChunkRepository implements RtmsBackfillChunkRepository {

	private final JdbcClient jdbcClient;
	private final Clock clock;

	public JdbcRtmsBackfillChunkRepository(JdbcClient jdbcClient, Clock clock) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public int insertChunks(long jobId, List<RtmsBackfillChunkRequest> chunks, int maxAttemptCount) {
		int inserted = 0;
		for (RtmsBackfillChunkRequest chunk : chunks) {
			inserted += jdbcClient.sql("""
				INSERT INTO rtms_backfill_chunk (
				    job_id,
				    lawd_cd,
				    deal_ymd,
				    status,
				    max_attempt_count
				)
				VALUES (:jobId, :lawdCd, :dealYmd, 'PENDING', :maxAttemptCount)
				ON CONFLICT (job_id, lawd_cd, deal_ymd) DO NOTHING
				""")
				.param("jobId", jobId)
				.param("lawdCd", chunk.lawdCd())
				.param("dealYmd", chunk.dealYmd())
				.param("maxAttemptCount", maxAttemptCount)
				.update();
		}
		return inserted;
	}

	@Override
	public int recoverStaleRunning(long jobId, String failureReason) {
		Instant now = clock.instant();
		return jdbcClient.sql("""
			UPDATE rtms_backfill_chunk
			SET status = CASE
			        WHEN attempt_count >= max_attempt_count THEN 'BLOCKED'
			        ELSE 'FAILED'
			    END,
			    last_failure_reason = :failureReason,
			    locked_by = NULL,
			    locked_at = NULL,
			    locked_until = NULL,
			    heartbeat_at = NULL,
			    completed_at = CASE
			        WHEN attempt_count >= max_attempt_count THEN :now
			        ELSE completed_at
			    END,
			    updated_at = :now
			WHERE job_id = :jobId
			  AND status = 'RUNNING'
			  AND locked_until <= :now
			""")
			.param("jobId", jobId)
			.param("failureReason", failureReason)
			.param("now", offset(now))
			.update();
	}

	@Override
	public Optional<RtmsBackfillChunkClaim> claimNextRunnable(
		long jobId,
		String workerId,
		Duration leaseDuration
	) {
		Instant now = clock.instant();
		return jdbcClient.sql("""
			UPDATE rtms_backfill_chunk
			SET status = 'RUNNING',
			    attempt_count = attempt_count + 1,
			    locked_by = :workerId,
			    locked_at = :now,
			    locked_until = :lockedUntil,
			    heartbeat_at = :now,
			    started_at = COALESCE(started_at, :now),
			    completed_at = NULL,
			    updated_at = :now
			WHERE id = (
			    SELECT id
			    FROM rtms_backfill_chunk
			    WHERE job_id = :jobId
			      AND status IN ('PENDING', 'FAILED', 'PARTIAL')
			      AND attempt_count < max_attempt_count
			    ORDER BY deal_ymd, lawd_cd
			    FOR UPDATE SKIP LOCKED
			    LIMIT 1
			)
			RETURNING *
			""")
			.param("jobId", jobId)
			.param("workerId", workerId)
			.param("now", offset(now))
			.param("lockedUntil", offset(now.plus(leaseDuration)))
			.query(this::mapClaim)
			.optional();
	}

	@Override
	public void markCompleted(long chunkId, Long runId) {
		jdbcClient.sql("""
			UPDATE rtms_backfill_chunk
			SET status = 'COMPLETED',
			    last_run_id = :runId,
			    last_failure_reason = NULL,
			    locked_by = NULL,
			    locked_at = NULL,
			    locked_until = NULL,
			    heartbeat_at = NULL,
			    completed_at = :now,
			    updated_at = :now
			WHERE id = :chunkId
			""")
			.param("chunkId", chunkId)
			.param("runId", runId)
			.param("now", offset(clock.instant()))
			.update();
		recordChunkRun(chunkId, runId);
	}

	@Override
	public void markFailed(long chunkId, Long runId, String failureReason) {
		markProblem(chunkId, runId, failureReason, RtmsBackfillChunkStatus.FAILED);
	}

	@Override
	public void markPartial(long chunkId, Long runId, String failureReason) {
		markProblem(chunkId, runId, failureReason, RtmsBackfillChunkStatus.PARTIAL);
	}

	@Override
	public void markBlocked(long chunkId, String failureReason) {
		Instant now = clock.instant();
		jdbcClient.sql("""
			UPDATE rtms_backfill_chunk
			SET status = 'BLOCKED',
			    last_failure_reason = :failureReason,
			    locked_by = NULL,
			    locked_at = NULL,
			    locked_until = NULL,
			    heartbeat_at = NULL,
			    completed_at = :now,
			    updated_at = :now
			WHERE id = :chunkId
			""")
			.param("chunkId", chunkId)
			.param("failureReason", failureReason)
			.param("now", offset(now))
			.update();
	}

	@Override
	public Optional<RtmsBackfillChunkRecord> findById(long chunkId) {
		return jdbcClient.sql("""
			SELECT *
			FROM rtms_backfill_chunk
			WHERE id = :chunkId
			""")
			.param("chunkId", chunkId)
			.query(this::mapRecord)
			.optional();
	}

	@Override
	public RtmsBackfillChunkStatusCounts countStatuses(long jobId) {
		long pending = count(jobId, RtmsBackfillChunkStatus.PENDING);
		long running = count(jobId, RtmsBackfillChunkStatus.RUNNING);
		long completed = count(jobId, RtmsBackfillChunkStatus.COMPLETED);
		long partial = count(jobId, RtmsBackfillChunkStatus.PARTIAL);
		long failed = count(jobId, RtmsBackfillChunkStatus.FAILED);
		long blocked = count(jobId, RtmsBackfillChunkStatus.BLOCKED);
		long skipped = count(jobId, RtmsBackfillChunkStatus.SKIPPED);
		return new RtmsBackfillChunkStatusCounts(pending, running, completed, partial, failed, blocked, skipped);
	}

	private void markProblem(
		long chunkId,
		Long runId,
		String failureReason,
		RtmsBackfillChunkStatus retryableStatus
	) {
		Instant now = clock.instant();
		jdbcClient.sql("""
			UPDATE rtms_backfill_chunk
			SET status = CASE
			        WHEN attempt_count >= max_attempt_count THEN 'BLOCKED'
			        ELSE :status
			    END,
			    last_run_id = :runId,
			    last_failure_reason = :failureReason,
			    locked_by = NULL,
			    locked_at = NULL,
			    locked_until = NULL,
			    heartbeat_at = NULL,
			    completed_at = CASE
			        WHEN attempt_count >= max_attempt_count THEN :now
			        ELSE completed_at
			    END,
			    updated_at = :now
			WHERE id = :chunkId
			""")
			.param("chunkId", chunkId)
			.param("status", retryableStatus.name())
			.param("runId", runId)
			.param("failureReason", failureReason)
			.param("now", offset(now))
			.update();
		recordChunkRun(chunkId, runId);
	}

	private void recordChunkRun(long chunkId, Long runId) {
		if (runId == null) {
			return;
		}
		Integer attemptNo = jdbcClient.sql("""
			SELECT attempt_count
			FROM rtms_backfill_chunk
			WHERE id = :chunkId
			""")
			.param("chunkId", chunkId)
			.query(Integer.class)
			.single();
		jdbcClient.sql("""
			INSERT INTO rtms_backfill_chunk_run (
			    chunk_id,
			    run_id,
			    attempt_no
			)
			VALUES (:chunkId, :runId, :attemptNo)
			ON CONFLICT (chunk_id, run_id) DO NOTHING
			""")
			.param("chunkId", chunkId)
			.param("runId", runId)
			.param("attemptNo", attemptNo)
			.update();
	}

	private long count(long jobId, RtmsBackfillChunkStatus status) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM rtms_backfill_chunk
			WHERE job_id = :jobId
			  AND status = :status
			""")
			.param("jobId", jobId)
			.param("status", status.name())
			.query(Long.class)
			.single();
	}

	private RtmsBackfillChunkClaim mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RtmsBackfillChunkClaim(
			resultSet.getLong("id"),
			resultSet.getLong("job_id"),
			resultSet.getString("lawd_cd"),
			resultSet.getString("deal_ymd"),
			RtmsBackfillChunkStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("attempt_count")
		);
	}

	private RtmsBackfillChunkRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
		Long lastRunId = resultSet.getObject("last_run_id", Long.class);
		return new RtmsBackfillChunkRecord(
			resultSet.getLong("id"),
			resultSet.getLong("job_id"),
			resultSet.getString("lawd_cd"),
			resultSet.getString("deal_ymd"),
			RtmsBackfillChunkStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("attempt_count"),
			resultSet.getInt("max_attempt_count"),
			lastRunId,
			resultSet.getString("last_failure_reason")
		);
	}

	private OffsetDateTime offset(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
