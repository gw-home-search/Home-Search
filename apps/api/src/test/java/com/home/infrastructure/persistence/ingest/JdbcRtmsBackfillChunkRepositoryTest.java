package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.backfill.RtmsBackfillChunkClaim;
import com.home.application.ingest.backfill.RtmsBackfillChunkRequest;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatus;
import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.application.ingest.backfill.RtmsBackfillJobRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsBackfillChunkRepositoryTest extends JdbcPostgresTestSupport {

	private final Clock clock = Clock.fixed(Instant.parse("2026-06-03T00:00:00Z"), ZoneOffset.UTC);

	@Test
	@DisplayName("RTMS backfill chunk는 중복 생성 없이 다음 실행 대상을 claim하고 완료 chunk를 제외한다")
	void insertsChunksIdempotentlyAndClaimsNextRunnableChunk() {
		JdbcRtmsBackfillJobRepository jobRepository = new JdbcRtmsBackfillJobRepository(jdbcClient, clock);
		JdbcRtmsBackfillChunkRepository chunkRepository = new JdbcRtmsBackfillChunkRepository(jdbcClient, clock);
		RtmsBackfillJobRecord job = jobRepository.createIfAbsent(
			"rtms-national-201201-201202",
			"RTMS",
			"201201",
			"201202",
			"region.si-gun-gu",
			4
		);

		List<RtmsBackfillChunkRequest> chunks = List.of(
			new RtmsBackfillChunkRequest("11110", "201201"),
			new RtmsBackfillChunkRequest("11680", "201201")
		);
		assertThat(chunkRepository.insertChunks(job.id(), chunks, 3)).isEqualTo(2);
		assertThat(chunkRepository.insertChunks(job.id(), chunks, 3)).isZero();

		RtmsBackfillChunkClaim claim = chunkRepository.claimNextRunnable(
			job.id(),
			"worker-1",
			Duration.ofMinutes(30)
		).orElseThrow();
		assertThat(claim.status()).isEqualTo(RtmsBackfillChunkStatus.RUNNING);
		assertThat(claim.attemptCount()).isEqualTo(1);

		chunkRepository.markCompleted(claim.id(), null);

		RtmsBackfillChunkClaim next = chunkRepository.claimNextRunnable(
			job.id(),
			"worker-2",
			Duration.ofMinutes(30)
		).orElseThrow();
		assertThat(next.id()).isNotEqualTo(claim.id());
		assertThat(next.status()).isEqualTo(RtmsBackfillChunkStatus.RUNNING);
	}

	@Test
	@DisplayName("RTMS backfill chunk는 stale RUNNING을 복구하고 max attempt 이후 BLOCKED로 남긴다")
	void recoversStaleRunningChunksAndBlocksAfterMaxAttempts() {
		JdbcRtmsBackfillJobRepository jobRepository = new JdbcRtmsBackfillJobRepository(jdbcClient, clock);
		JdbcRtmsBackfillChunkRepository chunkRepository = new JdbcRtmsBackfillChunkRepository(jdbcClient, clock);
		RtmsBackfillJobRecord job = jobRepository.createIfAbsent(
			"rtms-national-stale",
			"RTMS",
			"201201",
			"201201",
			"region.si-gun-gu",
			1
		);
		chunkRepository.insertChunks(job.id(), List.of(new RtmsBackfillChunkRequest("11680", "201201")), 1);
		RtmsBackfillChunkClaim claim = chunkRepository.claimNextRunnable(
			job.id(),
			"worker-1",
			Duration.ZERO
		).orElseThrow();

		assertThat(chunkRepository.recoverStaleRunning(job.id(), "lease expired")).isEqualTo(1);

		assertThat(chunkRepository.findById(claim.id()).orElseThrow()).satisfies(chunk -> {
			assertThat(chunk.status()).isEqualTo(RtmsBackfillChunkStatus.BLOCKED);
			assertThat(chunk.lastFailureReason()).isEqualTo("lease expired");
		});
		assertThat(chunkRepository.claimNextRunnable(job.id(), "worker-2", Duration.ofMinutes(30))).isEmpty();
	}

	@Test
	@DisplayName("RTMS backfill chunk는 PARTIAL/FAILED/BLOCKED 상태와 run retry history를 조회 가능하게 남긴다")
	void recordsProblemStatesStatusCountsAndChunkRunHistory() {
		JdbcRtmsBackfillJobRepository jobRepository = new JdbcRtmsBackfillJobRepository(jdbcClient, clock);
		JdbcRtmsBackfillChunkRepository chunkRepository = new JdbcRtmsBackfillChunkRepository(jdbcClient, clock);
		JdbcRtmsIngestRunRepository runRepository = new JdbcRtmsIngestRunRepository(jdbcClient);
		RtmsBackfillJobRecord job = jobRepository.createIfAbsent(
			"rtms-national-problem-states",
			"RTMS",
			"201201",
			"201201",
			"region.si-gun-gu",
			2
		);
		chunkRepository.insertChunks(
			job.id(),
			List.of(
				new RtmsBackfillChunkRequest("11110", "201201"),
				new RtmsBackfillChunkRequest("11680", "201201")
			),
			3
		);
		RtmsBackfillChunkClaim first = chunkRepository.claimNextRunnable(
			job.id(),
			"worker-1",
			Duration.ofMinutes(30)
		).orElseThrow();
		RtmsIngestRunRecord partialRun = runRepository.save(RtmsIngestRunRecord.partiallyFailed(
			first.lawdCd(),
			first.dealYmd(),
			1,
			new IngestResult(1, 1, 1, 0, 0, 0),
			"IllegalStateException: page failed",
			clock.instant(),
			clock.instant()
		));

		chunkRepository.markPartial(first.id(), partialRun.id(), "IllegalStateException: page failed");
		chunkRepository.markBlocked(first.id(), "manual inspection required");
		RtmsBackfillChunkClaim second = chunkRepository.claimNextRunnable(
			job.id(),
			"worker-2",
			Duration.ofMinutes(30)
		).orElseThrow();
		RtmsIngestRunRecord failedRun = runRepository.save(RtmsIngestRunRecord.failed(
			second.lawdCd(),
			second.dealYmd(),
			0,
			IngestResult.empty(),
			"IllegalStateException: fetch failed",
			clock.instant(),
			clock.instant()
		));

		chunkRepository.markFailed(second.id(), failedRun.id(), "IllegalStateException: fetch failed");

		assertThat(chunkRepository.findById(first.id()).orElseThrow()).satisfies(chunk -> {
			assertThat(chunk.status()).isEqualTo(RtmsBackfillChunkStatus.BLOCKED);
			assertThat(chunk.lastRunId()).isEqualTo(partialRun.id());
			assertThat(chunk.lastFailureReason()).isEqualTo("manual inspection required");
		});
		assertThat(chunkRepository.findById(second.id()).orElseThrow()).satisfies(chunk -> {
			assertThat(chunk.status()).isEqualTo(RtmsBackfillChunkStatus.FAILED);
			assertThat(chunk.lastRunId()).isEqualTo(failedRun.id());
			assertThat(chunk.lastFailureReason()).isEqualTo("IllegalStateException: fetch failed");
		});
		assertThat(chunkRepository.countStatuses(job.id())).satisfies(counts -> {
			assertThat(counts.blocked()).isEqualTo(1);
			assertThat(counts.failed()).isEqualTo(1);
			assertThat(counts.problemCount()).isEqualTo(2);
		});
		assertThat(jdbcClient.sql("SELECT count(*) FROM rtms_backfill_chunk_run")
			.query(Long.class)
			.single()).isEqualTo(2);
	}
}
