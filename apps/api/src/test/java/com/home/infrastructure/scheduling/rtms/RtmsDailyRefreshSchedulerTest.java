package com.home.infrastructure.scheduling.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.region.RegionRelationSynchronizationResult;
import com.home.application.region.RegionUnitCntSynchronizationService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class RtmsDailyRefreshSchedulerTest {

	private static final Clock JUNE_2026_KST_CLOCK = Clock.fixed(
		Instant.parse("2026-06-09T16:00:00Z"),
		ZoneOffset.UTC
	);

	@Test
	@DisplayName("daily refresh scheduler는 기본 cron을 매일 새벽 3시 KST로 둔다")
	void schedulerDefaultCronRunsAtThreeAmKst() throws NoSuchMethodException {
		Scheduled scheduled = Arrays.stream(RtmsDailyRefreshScheduler.class.getDeclaredMethod("runDue")
				.getAnnotationsByType(Scheduled.class))
			.findFirst()
			.orElseThrow();

		assertThat(scheduled.cron()).isEqualTo("${home.ingest.rtms.daily.cron:0 0 3 * * *}");
		assertThat(scheduled.zone()).isEqualTo("${home.ingest.rtms.daily.zone:Asia/Seoul}");
	}

	@Test
	@DisplayName("daily refresh scheduler는 configured 법정동마다 KST 현재월 lookback plan을 실행하고 Slack summary를 보낸다")
	void schedulerRunsConfiguredLawdPlansAndSendsSlackSummary() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		CapturingDailyRefreshNotifier notifier = new CapturingDailyRefreshNotifier();
		AtomicInteger syncCalls = new AtomicInteger();
		RegionUnitCntSynchronizationService synchronizationService = new RegionUnitCntSynchronizationService(() -> {
			syncCalls.incrementAndGet();
			return new RegionRelationSynchronizationResult(true, true, false);
		});
		RtmsDailyRefreshScheduler scheduler = scheduler(
			monthlyRefreshRunner,
			notifier,
			List.of("11680", "11710"),
			synchronizationService
		);
		RtmsMonthlyRefreshPlan gangnamPlan = new RtmsMonthlyRefreshPlan("11680", "202606", 1);
		RtmsMonthlyRefreshPlan songpaPlan = new RtmsMonthlyRefreshPlan("11710", "202606", 1);
		when(monthlyRefreshRunner.refresh(gangnamPlan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(2, 2, 1, 1, 0, 0), 11L),
			RtmsMonthlyRefreshRunSummary.completed("11680", "202605", 1, new IngestResult(1, 1, 0, 1, 0, 0), 12L)
		)));
		when(monthlyRefreshRunner.refresh(songpaPlan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11710", "202606", 2, new IngestResult(3, 3, 0, 3, 0, 0), 21L),
			RtmsMonthlyRefreshRunSummary.completed("11710", "202605", 1, new IngestResult(1, 1, 0, 1, 0, 0), 22L)
		)));

		scheduler.runDue();

		verify(monthlyRefreshRunner).refresh(gangnamPlan);
		verify(monthlyRefreshRunner).refresh(songpaPlan);
		assertThat(syncCalls).hasValue(1);
		assertThat(notifier.messages()).hasSize(1);
		assertThat(notifier.messages().get(0))
			.contains("RTMS daily refresh")
			.contains("lawdCd=11680")
			.contains("dealYmds=[202606, 202605]")
			.contains("normalizedInserted=1")
			.contains("runIds=[11, 12]")
			.contains("lawdCd=11710")
			.contains("regionSync=COMPLETED")
			.contains("신규 실거래 저장");
	}

	@Test
	@DisplayName("daily refresh scheduler는 region sync 실패를 FAILED Slack summary로 남긴다")
	void schedulerReportsRegionSyncFailure() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		CapturingDailyRefreshNotifier notifier = new CapturingDailyRefreshNotifier();
		RegionUnitCntSynchronizationService synchronizationService = new RegionUnitCntSynchronizationService(() -> {
			throw new IllegalStateException("region sync failed");
		});
		RtmsDailyRefreshScheduler scheduler = scheduler(
			monthlyRefreshRunner,
			notifier,
			List.of("11680"),
			synchronizationService
		);
		RtmsMonthlyRefreshPlan plan = new RtmsMonthlyRefreshPlan("11680", "202606", 1);
		when(monthlyRefreshRunner.refresh(plan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(1, 1, 0, 1, 0, 0), 31L),
			RtmsMonthlyRefreshRunSummary.completed("11680", "202605", 1, new IngestResult(1, 1, 0, 1, 0, 0), 32L)
		)));

		assertThatCode(scheduler::runDue).doesNotThrowAnyException();

		assertThat(notifier.messages()).singleElement()
			.asString()
			.contains("regionSync=FAILED")
			.contains("region sync failed");
	}

	@Test
	@DisplayName("daily refresh scheduler는 configured 법정동이 없어도 실행한 region sync 결과를 Slack summary로 남긴다")
	void schedulerReportsRegionSyncWhenLawdCodesAreEmpty() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		CapturingDailyRefreshNotifier notifier = new CapturingDailyRefreshNotifier();
		RtmsDailyRefreshScheduler scheduler = scheduler(
			monthlyRefreshRunner,
			notifier,
			List.of(),
			new RegionUnitCntSynchronizationService(
				() -> new RegionRelationSynchronizationResult(false, false, true)
			)
		);

		scheduler.runDue();

		assertThat(notifier.messages()).singleElement()
			.asString()
			.contains("status=PARTIAL")
			.contains("regionSync=PARTIAL");
	}

	@Test
	@DisplayName("daily refresh scheduler는 Hermes Slack 전송 실패가 나도 ingest 결과를 예외로 뒤집지 않는다")
	void schedulerDoesNotFailWhenSlackNotificationFails() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsDailyRefreshScheduler scheduler = scheduler(monthlyRefreshRunner, message -> {
			throw new IllegalStateException("Hermes Slack send failed serviceKey=PRIVATE_VALUE");
		}, List.of("11680"));
		RtmsMonthlyRefreshPlan plan = new RtmsMonthlyRefreshPlan("11680", "202606", 1);
		when(monthlyRefreshRunner.refresh(plan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(1, 1, 0, 1, 0, 0), 31L),
			RtmsMonthlyRefreshRunSummary.completed("11680", "202605", 1, new IngestResult(1, 1, 0, 1, 0, 0), 32L)
		)));

		assertThatCode(scheduler::runDue).doesNotThrowAnyException();

		verify(monthlyRefreshRunner).refresh(plan);
	}

	@Test
	@DisplayName("daily refresh scheduler는 어떤 live fetch보다 먼저 coordinate-source preflight를 검증한다")
	void schedulerVerifiesCoordinateSourcePreflightBeforeAnyLiveFetch() {
		List<String> callOrder = new ArrayList<>();
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		when(monthlyRefreshRunner.refresh(any(RtmsMonthlyRefreshPlan.class))).thenAnswer(invocation -> {
			callOrder.add("refresh");
			return new RtmsMonthlyRefreshReport(List.of());
		});
		RtmsDailyRefreshScheduler scheduler = scheduler(
			monthlyRefreshRunner,
			() -> callOrder.add("preflight"),
			new CapturingDailyRefreshNotifier(),
			List.of("11680", "11710")
		);

		scheduler.runDue();

		assertThat(callOrder).containsExactly("preflight", "refresh", "refresh");
	}

	@Test
	@DisplayName("daily refresh scheduler는 preflight 실패 시 어떤 법정동도 fetch하지 않고 FAILED summary를 보낸다")
	void schedulerSendsFailedSummaryWithoutFetchingWhenPreflightFails() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		CapturingDailyRefreshNotifier notifier = new CapturingDailyRefreshNotifier();
		RtmsCoordinateSourcePreflight failingPreflight = () -> {
			throw new IllegalStateException("COORDINATE_SOURCE_DB_JDBC_URL is required for RTMS ingest");
		};
		RtmsDailyRefreshScheduler scheduler = scheduler(
			monthlyRefreshRunner,
			failingPreflight,
			notifier,
			List.of("11680", "11710")
		);

		assertThatCode(scheduler::runDue).doesNotThrowAnyException();

		verifyNoInteractions(monthlyRefreshRunner);
		assertThat(notifier.messages()).hasSize(1);
		assertThat(notifier.messages().get(0))
			.contains("status=FAILED")
			.contains("lawdCd=11680")
			.contains("lawdCd=11710")
			.contains("COORDINATE_SOURCE_DB_JDBC_URL is required");
	}

	@Test
	@DisplayName("daily refresh scheduler는 invalid 법정동을 실패 summary로 남기고 다음 법정동을 계속 실행한다")
	void schedulerKeepsRunningNextLawdWhenOneLawdCodeIsInvalid() {
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		CapturingDailyRefreshNotifier notifier = new CapturingDailyRefreshNotifier();
		RtmsDailyRefreshScheduler scheduler = scheduler(monthlyRefreshRunner, notifier, List.of("invalid", "11680"));
		RtmsMonthlyRefreshPlan validPlan = new RtmsMonthlyRefreshPlan("11680", "202606", 1);
		when(monthlyRefreshRunner.refresh(validPlan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(1, 1, 0, 1, 0, 0), 41L),
			RtmsMonthlyRefreshRunSummary.completed("11680", "202605", 1, new IngestResult(1, 1, 0, 1, 0, 0), 42L)
		)));

		scheduler.runDue();

		verify(monthlyRefreshRunner).refresh(validPlan);
		assertThat(notifier.messages()).hasSize(1);
		assertThat(notifier.messages().get(0))
			.contains("lawdCd=invalid")
			.contains("status=FAILED")
			.contains("lawdCd=11680")
			.contains("status=PARTIAL");
	}

	private static RtmsDailyRefreshScheduler scheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsDailyRefreshNotifier notifier
	) {
		return scheduler(monthlyRefreshRunner, notifier, List.of("11680", "11710"));
	}

	private static RtmsDailyRefreshScheduler scheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsDailyRefreshNotifier notifier,
		List<String> lawdCds
	) {
		return scheduler(
			monthlyRefreshRunner,
			RtmsCoordinateSourcePreflight.noop(),
			notifier,
			lawdCds,
			new RegionUnitCntSynchronizationService(
				() -> new RegionRelationSynchronizationResult(false, false, false)
			)
		);
	}

	private static RtmsDailyRefreshScheduler scheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight,
		RtmsDailyRefreshNotifier notifier,
		List<String> lawdCds
	) {
		return scheduler(
			monthlyRefreshRunner,
			coordinateSourcePreflight,
			notifier,
			lawdCds,
			new RegionUnitCntSynchronizationService(
				() -> new RegionRelationSynchronizationResult(false, false, false)
			)
		);
	}

	private static RtmsDailyRefreshScheduler scheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsDailyRefreshNotifier notifier,
		List<String> lawdCds,
		RegionUnitCntSynchronizationService synchronizationService
	) {
		return scheduler(
			monthlyRefreshRunner,
			RtmsCoordinateSourcePreflight.noop(),
			notifier,
			lawdCds,
			synchronizationService
		);
	}

	private static RtmsDailyRefreshScheduler scheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight,
		RtmsDailyRefreshNotifier notifier,
		List<String> lawdCds,
		RegionUnitCntSynchronizationService synchronizationService
	) {
		return new RtmsDailyRefreshScheduler(
			monthlyRefreshRunner,
			coordinateSourcePreflight,
			new RtmsDailyRefreshProperties(lawdCds, 1, ZoneId.of("Asia/Seoul")),
			new RtmsDailyRefreshSlackMessageFormatter(),
			notifier,
			JUNE_2026_KST_CLOCK,
			synchronizationService
		);
	}

	private static final class CapturingDailyRefreshNotifier implements RtmsDailyRefreshNotifier {

		private final List<String> messages = new ArrayList<>();

		@Override
		public void send(String message) {
			messages.add(message);
		}

		List<String> messages() {
			return messages;
		}
	}
}
