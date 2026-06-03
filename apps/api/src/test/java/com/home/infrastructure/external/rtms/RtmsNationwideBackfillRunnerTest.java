package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.RtmsBackfillChunkClaim;
import com.home.application.ingest.RtmsBackfillChunkRecord;
import com.home.application.ingest.RtmsBackfillChunkRepository;
import com.home.application.ingest.RtmsBackfillChunkRequest;
import com.home.application.ingest.RtmsBackfillChunkStatus;
import com.home.application.ingest.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.RtmsBackfillJobRecord;
import com.home.application.ingest.RtmsBackfillJobRepository;
import com.home.application.ingest.RtmsBackfillJobStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsNationwideBackfillRunnerTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-06-03T00:00:00Z"), ZoneOffset.UTC);

	@Test
	@DisplayName("전국 backfill runner는 실패 chunk를 남기고 다음 chunk를 계속 실행한 뒤 job을 PARTIAL로 확정한다")
	void nationwideBackfillRunnerContinuesAfterChunkFailureAndMarksPartialJob() {
		RecordingJobRepository jobRepository = new RecordingJobRepository();
		RecordingChunkRepository chunkRepository = new RecordingChunkRepository();
		List<RtmsBackfillChunkRequest> chunks = List.of(
			new RtmsBackfillChunkRequest("11110", "201201"),
			new RtmsBackfillChunkRequest("11680", "201201"),
			new RtmsBackfillChunkRequest("11710", "201201")
		);
		RtmsNationwideBackfillPlan plan = new RtmsNationwideBackfillPlan(
			"rtms-national-201201",
			List.of("11110", "11680", "11710"),
			"201201",
			"201201"
		);
		RtmsNationwideBackfillRunner runner = new RtmsNationwideBackfillRunner(
			jobRepository,
			chunkRepository,
			request -> {
				if (request.lawdCd().equals("11680")) {
					return RtmsBackfillChunkExecutionResult.failed(
						request.lawdCd(),
						request.dealYmd(),
						20L,
						"IllegalStateException: temporary 503",
						IngestResult.empty()
					);
				}
				return RtmsBackfillChunkExecutionResult.completed(
					request.lawdCd(),
					request.dealYmd(),
					10L,
					new IngestResult(1, 1, 1, 0, 0, 0)
				);
			},
			clock,
			new RtmsNationwideBackfillOptions("worker-1", Duration.ofMinutes(30), 10)
		);

		RtmsNationwideBackfillReport report = runner.run(plan);

		assertThat(chunkRepository.inserted).containsExactlyElementsOf(chunks);
		assertThat(report.completedCount()).isEqualTo(2);
		assertThat(report.failedCount()).isEqualTo(1);
		assertThat(report.jobStatus()).isEqualTo(RtmsBackfillJobStatus.PARTIAL);
		assertThat(jobRepository.status).isEqualTo(RtmsBackfillJobStatus.PARTIAL);
		assertThat(chunkRepository.records.values())
			.extracting(RtmsBackfillChunkRecord::status)
			.containsExactly(
				RtmsBackfillChunkStatus.COMPLETED,
				RtmsBackfillChunkStatus.FAILED,
				RtmsBackfillChunkStatus.COMPLETED
			);
	}

	@Test
	@DisplayName("전국 backfill runner는 stale RUNNING 복구와 max attempt BLOCKED chunk를 실행 대상에서 제외한다")
	void nationwideBackfillRunnerRecoversStaleAndSkipsBlockedChunks() {
		RecordingJobRepository jobRepository = new RecordingJobRepository();
		RecordingChunkRepository chunkRepository = new RecordingChunkRepository();
		chunkRepository.recoveredStaleCount = 1;
		chunkRepository.nextClaim = Optional.empty();
		RtmsNationwideBackfillPlan plan = new RtmsNationwideBackfillPlan(
			"rtms-national-empty",
			List.of("11680"),
			"201201",
			"201201"
		);
		RtmsNationwideBackfillRunner runner = new RtmsNationwideBackfillRunner(
			jobRepository,
			chunkRepository,
			request -> RtmsBackfillChunkExecutionResult.completed(
				request.lawdCd(),
				request.dealYmd(),
				10L,
				IngestResult.empty()
			),
			clock,
			new RtmsNationwideBackfillOptions("worker-1", Duration.ofMinutes(30), 10)
		);

		RtmsNationwideBackfillReport report = runner.run(plan);

		assertThat(report.recoveredStaleCount()).isEqualTo(1);
		assertThat(report.jobStatus()).isEqualTo(RtmsBackfillJobStatus.PARTIAL);
		assertThat(chunkRepository.executedRequests).isEmpty();
	}

	private static final class RecordingJobRepository implements RtmsBackfillJobRepository {

		private RtmsBackfillJobStatus status = RtmsBackfillJobStatus.PLANNED;

		@Override
		public RtmsBackfillJobRecord createIfAbsent(
			String jobKey,
			String source,
			String dealYmdFrom,
			String dealYmdTo,
			String lawdCodeSource,
			int totalChunkCount
		) {
			return new RtmsBackfillJobRecord(1L, jobKey, source, dealYmdFrom, dealYmdTo, lawdCodeSource, status);
		}

		@Override
		public void markRunning(long jobId) {
			status = RtmsBackfillJobStatus.RUNNING;
		}

		@Override
		public void markCompleted(long jobId) {
			status = RtmsBackfillJobStatus.COMPLETED;
		}

		@Override
		public void markPartial(long jobId, String failureReason) {
			status = RtmsBackfillJobStatus.PARTIAL;
		}
	}

	private static final class RecordingChunkRepository implements RtmsBackfillChunkRepository {

		private final Map<Long, RtmsBackfillChunkRecord> records = new HashMap<>();
		private final List<RtmsBackfillChunkRequest> inserted = new ArrayList<>();
		private final List<RtmsBackfillChunkRequest> executedRequests = new ArrayList<>();
		private int nextId = 1;
		private int recoveredStaleCount;
		private Optional<RtmsBackfillChunkClaim> nextClaim;

		@Override
		public int insertChunks(long jobId, List<RtmsBackfillChunkRequest> chunks, int maxAttemptCount) {
			for (RtmsBackfillChunkRequest chunk : chunks) {
				inserted.add(chunk);
				long id = nextId++;
				records.put(id, new RtmsBackfillChunkRecord(
					id,
					jobId,
					chunk.lawdCd(),
					chunk.dealYmd(),
					RtmsBackfillChunkStatus.PENDING,
					0,
					maxAttemptCount,
					null,
					null
				));
			}
			return chunks.size();
		}

		@Override
		public int recoverStaleRunning(long jobId, String failureReason) {
			return recoveredStaleCount;
		}

		@Override
		public Optional<RtmsBackfillChunkClaim> claimNextRunnable(
			long jobId,
			String workerId,
			Duration leaseDuration
		) {
			if (nextClaim != null) {
				Optional<RtmsBackfillChunkClaim> current = nextClaim;
				nextClaim = Optional.empty();
				return current;
			}
			return records.values().stream()
				.filter(record -> record.status() == RtmsBackfillChunkStatus.PENDING)
				.findFirst()
				.map(record -> {
					RtmsBackfillChunkRecord running = record.withStatus(RtmsBackfillChunkStatus.RUNNING)
						.withAttemptCount(record.attemptCount() + 1);
					records.put(record.id(), running);
					return new RtmsBackfillChunkClaim(
						running.id(),
						running.jobId(),
						running.lawdCd(),
						running.dealYmd(),
						running.status(),
						running.attemptCount()
					);
				});
		}

		@Override
		public void markCompleted(long chunkId, Long runId) {
			records.put(chunkId, records.get(chunkId).withStatus(RtmsBackfillChunkStatus.COMPLETED).withLastRunId(runId));
		}

		@Override
		public void markFailed(long chunkId, Long runId, String failureReason) {
			RtmsBackfillChunkRecord record = records.get(chunkId);
			records.put(
				chunkId,
				record.withStatus(RtmsBackfillChunkStatus.FAILED)
					.withLastRunId(runId)
					.withLastFailureReason(failureReason)
			);
		}

		@Override
		public void markPartial(long chunkId, Long runId, String failureReason) {
			RtmsBackfillChunkRecord record = records.get(chunkId);
			records.put(
				chunkId,
				record.withStatus(RtmsBackfillChunkStatus.PARTIAL)
					.withLastRunId(runId)
					.withLastFailureReason(failureReason)
			);
		}

		@Override
		public void markBlocked(long chunkId, String failureReason) {
			records.put(
				chunkId,
				records.get(chunkId).withStatus(RtmsBackfillChunkStatus.BLOCKED).withLastFailureReason(failureReason)
			);
		}

		@Override
		public Optional<RtmsBackfillChunkRecord> findById(long chunkId) {
			return Optional.ofNullable(records.get(chunkId));
		}

		@Override
		public RtmsBackfillChunkStatusCounts countStatuses(long jobId) {
			return RtmsBackfillChunkStatusCounts.from(records.values());
		}
	}
}
