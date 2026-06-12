package com.home.infrastructure.scheduling.rtms;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.home.infrastructure.scheduling.ScheduledJobExecutionTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class RtmsDailyRefreshScheduler {

	private static final Logger log = LoggerFactory.getLogger(RtmsDailyRefreshScheduler.class);
	private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	private final RtmsMonthlyRefreshRunner monthlyRefreshRunner;
	private final RtmsCoordinateSourcePreflight coordinateSourcePreflight;
	private final RtmsDailyRefreshProperties properties;
	private final RtmsDailyRefreshSlackMessageFormatter formatter;
	private final RtmsDailyRefreshNotifier notifier;
	private final Clock clock;
	private final ScheduledJobExecutionTemplate execution = new ScheduledJobExecutionTemplate("RTMS daily refresh");

	RtmsDailyRefreshScheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight,
		RtmsDailyRefreshProperties properties,
		RtmsDailyRefreshSlackMessageFormatter formatter,
		RtmsDailyRefreshNotifier notifier,
		Clock clock
	) {
		this.monthlyRefreshRunner = Objects.requireNonNull(monthlyRefreshRunner);
		this.coordinateSourcePreflight = Objects.requireNonNull(coordinateSourcePreflight);
		this.properties = Objects.requireNonNull(properties);
		this.formatter = Objects.requireNonNull(formatter);
		this.notifier = Objects.requireNonNull(notifier);
		this.clock = Objects.requireNonNull(clock);
	}

	@Scheduled(
		cron = "${home.ingest.rtms.daily.cron:0 0 3 * * *}",
		zone = "${home.ingest.rtms.daily.zone:Asia/Seoul}"
	)
	void runDue() {
		execution.execute(this::runScheduledExecution);
	}

	private void runScheduledExecution() {
		RtmsDailyRefreshExecution result = runOnce();
		if (result.results().isEmpty()) {
			log.warn("RTMS daily refresh skipped because configured lawdCds is empty");
			return;
		}
		notifySlack(result);
	}

	RtmsDailyRefreshExecution runOnce() {
		String baseDealYmd = YearMonth.now(clock.withZone(properties.zoneId())).format(DEAL_YMD_FORMATTER);
		try {
			coordinateSourcePreflight.verify();
		}
		catch (RuntimeException exception) {
			log.error("RTMS daily refresh blocked because coordinate-source preflight failed", exception);
			return preflightFailedExecution(baseDealYmd, exception);
		}
		List<RtmsDailyRefreshResult> results = new ArrayList<>();
		for (String lawdCd : properties.lawdCds()) {
			try {
				RtmsMonthlyRefreshPlan plan = new RtmsMonthlyRefreshPlan(lawdCd, baseDealYmd, properties.lookbackMonths());
				results.add(RtmsDailyRefreshResult.from(plan, monthlyRefreshRunner.refresh(plan)));
			}
			catch (RuntimeException exception) {
				results.add(RtmsDailyRefreshResult.failed(
					lawdCd,
					baseDealYmd,
					properties.lookbackMonths(),
					exception
				));
			}
		}
		return new RtmsDailyRefreshExecution(results);
	}

	private RtmsDailyRefreshExecution preflightFailedExecution(String baseDealYmd, RuntimeException exception) {
		List<RtmsDailyRefreshResult> results = properties.lawdCds().stream()
			.map(lawdCd -> RtmsDailyRefreshResult.failed(
				lawdCd,
				baseDealYmd,
				properties.lookbackMonths(),
				exception
			))
			.toList();
		return new RtmsDailyRefreshExecution(results);
	}

	private void notifySlack(RtmsDailyRefreshExecution execution) {
		String message = formatter.format(execution);
		try {
			notifier.send(message);
		}
		catch (RuntimeException exception) {
			log.warn(
				"RTMS daily refresh Hermes Slack notification failed status={} reason={}",
				execution.status(),
				formatter.sanitizeSensitiveValues(exception.getMessage())
			);
		}
	}
}
