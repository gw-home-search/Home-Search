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
	private final RtmsNationwideBackfillRunner nationwideBackfillRunner;
	private final RtmsOneShotIngestProperties properties;
	private final RtmsApartmentTradeProperties tradeProperties;
	private final RtmsCoordinateSourcePreflight coordinateSourcePreflight;

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		this(runner, null, null, properties, tradeProperties, RtmsCoordinateSourcePreflight.noop());
	}

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight
	) {
		this(runner, null, null, properties, tradeProperties, coordinateSourcePreflight);
	}

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		this(runner, monthlyRefreshRunner, null, properties, tradeProperties, RtmsCoordinateSourcePreflight.noop());
	}

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight
	) {
		this(runner, monthlyRefreshRunner, null, properties, tradeProperties, coordinateSourcePreflight);
	}

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsNationwideBackfillRunner nationwideBackfillRunner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties,
		RtmsCoordinateSourcePreflight coordinateSourcePreflight
	) {
		this.runner = runner;
		this.monthlyRefreshRunner = monthlyRefreshRunner;
		this.nationwideBackfillRunner = nationwideBackfillRunner;
		this.properties = properties;
		this.tradeProperties = tradeProperties;
		this.coordinateSourcePreflight = coordinateSourcePreflight;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}

		RtmsIngestMode mode = properties.ingestMode();
		tradeProperties.requiredServiceKey();
		coordinateSourcePreflight.verify();
		if (mode == RtmsIngestMode.MONTHLY_REFRESH) {
			runMonthlyRefresh();
			return;
		}
		if (mode == RtmsIngestMode.NATIONWIDE_BACKFILL) {
			runNationwideBackfill();
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

	private void runNationwideBackfill() {
		RtmsNationwideBackfillPlan plan = properties.nationwideBackfillPlan();
		RtmsNationwideBackfillOptions options = properties.nationwideBackfillOptions();
		if (properties.preflightOnly()) {
			log.info(
				"RTMS nationwide backfill preflight completed baseUrl={} path={} jobKey={} dealYmdFrom={} "
					+ "dealYmdTo={} lawdCount={} chunkCount={} workerId={} chunkLimit={} numOfRows={}",
				tradeProperties.baseUrl(),
				tradeProperties.path(),
				plan.jobKey(),
				plan.dealYmdFrom(),
				plan.dealYmdTo(),
				plan.lawdCds().size(),
				plan.chunks().size(),
				options.workerId(),
				options.chunkLimit(),
				tradeProperties.numOfRows()
			);
			return;
		}
		if (nationwideBackfillRunner == null) {
			throw new IllegalStateException("RtmsNationwideBackfillRunner is required for RTMS nationwide backfill");
		}
		RtmsNationwideBackfillReport report = nationwideBackfillRunner.run(plan);
		log.info(
			"RTMS nationwide backfill completed jobId={} jobStatus={} completedChunks={} failedChunks={} "
				+ "partialChunks={} blockedChunks={} recoveredStaleChunks={}",
			report.jobId(),
			report.jobStatus(),
			report.statusCounts().completed(),
			report.statusCounts().failed(),
			report.statusCounts().partial(),
			report.statusCounts().blocked(),
			report.recoveredStaleCount()
		);
	}
}
