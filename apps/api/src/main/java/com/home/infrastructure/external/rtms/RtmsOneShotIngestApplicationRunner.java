package com.home.infrastructure.external.rtms;

import com.home.application.ingest.IngestResult;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class RtmsOneShotIngestApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RtmsOneShotIngestApplicationRunner.class);

	private final RtmsOneShotTradeIngestRunner runner;
	private final RtmsMonthlyRefreshRunner monthlyRefreshRunner;
	private final RtmsOneShotIngestProperties properties;
	private final RtmsApartmentTradeProperties tradeProperties;

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		this(runner, null, properties, tradeProperties);
	}

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		this.runner = runner;
		this.monthlyRefreshRunner = monthlyRefreshRunner;
		this.properties = properties;
		this.tradeProperties = tradeProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}

		RtmsIngestMode mode = properties.ingestMode();
		tradeProperties.requiredServiceKey();
		if (mode == RtmsIngestMode.MONTHLY_REFRESH) {
			runMonthlyRefresh();
			return;
		}
		runOneShot();
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.RTMS_ONE_SHOT_INGEST;
	}

	private void runOneShot() {
		RtmsApartmentTradeRequest request = properties.request();
		if (properties.preflightOnly()) {
			log.info(
				"RTMS one-shot ingest preflight completed baseUrl={} path={} lawdCd={} dealYmd={} pageNo={} "
					+ "numOfRows={}",
				tradeProperties.baseUrl(),
				tradeProperties.path(),
				request.lawdCd(),
				request.dealYmd(),
				request.pageNo(),
				tradeProperties.numOfRows()
			);
			return;
		}
		IngestResult result = runner.ingest(request);
		log.info(
			"RTMS one-shot ingest completed lawdCd={} dealYmd={} pageNo={} read={} rawSaved={} "
				+ "normalizedInserted={} duplicateSkipped={} canceledSkipped={} matchFailed={} parseFailed={}",
			request.lawdCd(),
			request.dealYmd(),
			request.pageNo(),
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.canceledSkipped(),
			result.matchFailed(),
			result.parseFailed()
		);
	}

	private void runMonthlyRefresh() {
		RtmsMonthlyRefreshPlan plan = properties.monthlyRefreshPlan();
		if (properties.preflightOnly()) {
			log.info(
				"RTMS monthly refresh preflight completed baseUrl={} path={} lawdCd={} dealYmds={} "
					+ "lookbackMonths={} numOfRows={}",
				tradeProperties.baseUrl(),
				tradeProperties.path(),
				plan.lawdCd(),
				plan.dealYmds(),
				plan.lookbackMonths(),
				tradeProperties.numOfRows()
			);
			return;
		}
		if (monthlyRefreshRunner == null) {
			throw new IllegalStateException("RtmsMonthlyRefreshRunner is required for RTMS monthly refresh ingest");
		}
		RtmsMonthlyRefreshReport report = monthlyRefreshRunner.refresh(plan);
		IngestResult total = report.totalResult();
		log.info(
			"RTMS monthly refresh completed lawdCd={} dealYmds={} monthCount={} pageCount={} read={} rawSaved={} "
				+ "normalizedInserted={} duplicateSkipped={} canceledSkipped={} matchFailed={} parseFailed={} hasNewData={}",
			plan.lawdCd(),
			plan.dealYmds(),
			report.runs().size(),
			report.totalPageCount(),
			total.read(),
			total.rawSaved(),
			total.normalizedInserted(),
			total.duplicateSkipped(),
			total.canceledSkipped(),
			total.matchFailed(),
			total.parseFailed(),
			report.hasNewData()
		);
	}
}
