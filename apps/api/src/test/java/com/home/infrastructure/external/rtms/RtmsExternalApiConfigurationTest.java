package com.home.infrastructure.external.rtms;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.backfill.RtmsBackfillChunkClaim;
import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.domain.ingest.backfill.RtmsBackfillChunkStatus;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.backfill.RtmsBackfillJobRecord;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;
import com.home.domain.ingest.backfill.RtmsBackfillJobStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RtmsExternalApiConfigurationTest {

	private final ApplicationContextRunner propertiesContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	@DisplayName("RTMS one-shot 설정은 기존 property key를 의미 단위 설정 객체로 바인딩한다")
	void rtmsOneShotPropertiesBindExistingKeys() {
		propertiesContextRunner
			.withPropertyValues(
				"home.ingest.rtms.enabled=true",
				"home.ingest.rtms.lawd-cd=11680",
				"home.ingest.rtms.deal-ymd=202606",
				"home.ingest.rtms.page-no=2",
				"home.ingest.rtms.preflight-only=true",
				"home.ingest.rtms.mode=nationwide-backfill",
				"home.ingest.rtms.lookback-months=3",
				"home.ingest.rtms.allow-coordinate-pending-only=true",
				"home.ingest.rtms.nationwide.lawd-cds=11110,11680",
				"home.ingest.rtms.nationwide.deal-ymd-from=202501",
				"home.ingest.rtms.nationwide.deal-ymd-to=202502",
				"home.ingest.rtms.nationwide.job-key=rtms-test",
				"home.ingest.rtms.nationwide.worker-id=worker-1",
				"home.ingest.rtms.nationwide.lease-minutes=45",
				"home.ingest.rtms.nationwide.max-attempt-count=4",
				"home.ingest.rtms.nationwide.chunk-limit=6"
			)
			.run(context -> {
				RtmsOneShotIngestConfigurationProperties bound =
					context.getBean(RtmsOneShotIngestConfigurationProperties.class);
				RtmsOneShotIngestProperties properties = bound.toProperties();

				org.assertj.core.api.Assertions.assertThat(properties).satisfies(value -> {
					org.assertj.core.api.Assertions.assertThat(value.request())
						.isEqualTo(new RtmsApartmentTradeRequest("11680", "202606", 2));
					org.assertj.core.api.Assertions.assertThat(value.preflightOnly()).isTrue();
					org.assertj.core.api.Assertions.assertThat(value.lookbackMonths()).isEqualTo(3);
					org.assertj.core.api.Assertions.assertThat(value.allowCoordinatePendingOnly()).isTrue();
					org.assertj.core.api.Assertions.assertThat(value.nationwideBackfillPlan().jobKey()).isEqualTo("rtms-test");
					org.assertj.core.api.Assertions.assertThat(value.nationwideBackfillOptions().workerId()).isEqualTo("worker-1");
					org.assertj.core.api.Assertions.assertThat(value.nationwideBackfillOptions().leaseDuration())
						.isEqualTo(java.time.Duration.ofMinutes(45));
					org.assertj.core.api.Assertions.assertThat(value.nationwideBackfillOptions().maxAttemptCount()).isEqualTo(4);
					org.assertj.core.api.Assertions.assertThat(value.nationwideBackfillOptions().chunkLimit()).isEqualTo(6);
				});
			});
	}

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
			provider(RtmsBackfillJobRepository.class, jobRepository),
			provider(RtmsBackfillChunkRepository.class, chunkRepository),
			properties
		);

		runner.run(plan);

		verify(chunkRepository).insertChunks(1L, plan.chunks(), 3);
		verify(chunkRepository).markCompleted(101L, 11L);
		verify(chunkRepository).markPartial(102L, 12L, "IllegalStateException: page failed");
		verify(chunkRepository).markFailed(103L, 13L, "IllegalStateException: fetch failed");
		verify(jobRepository).markPartial(1L, "RTMS backfill finished with failed, partial, or blocked chunks");
	}

	private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean(type.getName(), bean);
		return beanFactory.getBeanProvider(type);
	}

	@EnableConfigurationProperties(RtmsOneShotIngestConfigurationProperties.class)
	private static class PropertiesConfiguration {
	}
}
