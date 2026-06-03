package com.home.infrastructure.external.rtms;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.RtmsBackfillChunkClaim;
import com.home.application.ingest.RtmsBackfillChunkRepository;
import com.home.application.ingest.RtmsBackfillChunkStatus;
import com.home.application.ingest.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.RtmsBackfillJobRecord;
import com.home.application.ingest.RtmsBackfillJobRepository;
import com.home.application.ingest.RtmsBackfillJobStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsExternalApiConfigurationTest {

	@Test
	@DisplayName("nationwide backfill runner bean은 monthly summary를 completed/partial/failed chunk 상태로 변환한다")
	void nationwideBackfillRunnerBeanMapsMonthlySummaryStatusesToChunkResults() {
		RtmsExternalApiConfiguration configuration = new RtmsExternalApiConfiguration();
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsBackfillJobRepository jobRepository = mock(RtmsBackfillJobRepository.class);
		RtmsBackfillChunkRepository chunkRepository = mock(RtmsBackfillChunkRepository.class);
		RtmsOneShotIngestProperties properties = new RtmsOneShotIngestProperties(
			true,
			"11680",
			"202606",
			1,
			false,
			"nationwide-backfill",
			0,
			false,
			"11110,11680,11710",
			"201201",
			"201201",
			"rtms-national-test",
			"worker-1",
			30,
			3,
			3
		);
		RtmsNationwideBackfillPlan plan = properties.nationwideBackfillPlan();
		when(jobRepository.createIfAbsent(
			"rtms-national-test",
			"RTMS",
			"201201",
			"201201",
			"region.si-gun-gu",
			3
		)).thenReturn(new RtmsBackfillJobRecord(
			1L,
			"rtms-national-test",
			"RTMS",
			"201201",
			"201201",
			"region.si-gun-gu",
			RtmsBackfillJobStatus.PLANNED
		));
		when(chunkRepository.claimNextRunnable(1L, "worker-1", java.time.Duration.ofMinutes(30)))
			.thenReturn(Optional.of(new RtmsBackfillChunkClaim(
				101L,
				1L,
				"11110",
				"201201",
				RtmsBackfillChunkStatus.RUNNING,
				1
			)))
			.thenReturn(Optional.of(new RtmsBackfillChunkClaim(
				102L,
				1L,
				"11680",
				"201201",
				RtmsBackfillChunkStatus.RUNNING,
				1
			)))
			.thenReturn(Optional.of(new RtmsBackfillChunkClaim(
				103L,
				1L,
				"11710",
				"201201",
				RtmsBackfillChunkStatus.RUNNING,
				1
			)))
			.thenReturn(Optional.empty());
		when(monthlyRefreshRunner.refresh("11110", "201201"))
			.thenReturn(RtmsMonthlyRefreshRunSummary.completed(
				"11110",
				"201201",
				1,
				new IngestResult(1, 1, 1, 0, 0, 0),
				11L
			));
		when(monthlyRefreshRunner.refresh("11680", "201201"))
			.thenReturn(RtmsMonthlyRefreshRunSummary.partiallyFailed(
				"11680",
				"201201",
				1,
				new IngestResult(1, 1, 1, 0, 0, 0),
				"IllegalStateException: page failed",
				12L
			));
		when(monthlyRefreshRunner.refresh("11710", "201201"))
			.thenReturn(RtmsMonthlyRefreshRunSummary.failed(
				"11710",
				"201201",
				0,
				IngestResult.empty(),
				"IllegalStateException: fetch failed",
				13L
			));
		when(chunkRepository.countStatuses(1L))
			.thenReturn(new RtmsBackfillChunkStatusCounts(0, 0, 1, 1, 1, 0, 0));

		RtmsNationwideBackfillRunner runner = configuration.rtmsNationwideBackfillRunner(
			monthlyRefreshRunner,
			jobRepository,
			chunkRepository,
			properties
		);

		runner.run(plan);

		verify(chunkRepository).insertChunks(1L, plan.chunks(), 3);
		verify(chunkRepository).markCompleted(101L, 11L);
		verify(chunkRepository).markPartial(102L, 12L, "IllegalStateException: page failed");
		verify(chunkRepository).markFailed(103L, 13L, "IllegalStateException: fetch failed");
		verify(jobRepository).markPartial(1L, "RTMS backfill finished with failed, partial, or blocked chunks");
	}
}
