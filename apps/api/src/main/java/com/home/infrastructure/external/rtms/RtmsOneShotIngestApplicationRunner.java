package com.home.infrastructure.external.rtms;

import com.home.application.ingest.IngestResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

class RtmsOneShotIngestApplicationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RtmsOneShotIngestApplicationRunner.class);

	private final RtmsOneShotTradeIngestRunner runner;
	private final RtmsOneShotIngestProperties properties;
	private final RtmsApartmentTradeProperties tradeProperties;

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties,
		RtmsApartmentTradeProperties tradeProperties
	) {
		this.runner = runner;
		this.properties = properties;
		this.tradeProperties = tradeProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}

		RtmsApartmentTradeRequest request = properties.request();
		tradeProperties.requiredServiceKey();
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
				+ "normalizedInserted={} duplicateSkipped={} matchFailed={} parseFailed={}",
			request.lawdCd(),
			request.dealYmd(),
			request.pageNo(),
			result.read(),
			result.rawSaved(),
			result.normalizedInserted(),
			result.duplicateSkipped(),
			result.matchFailed(),
			result.parseFailed()
		);
	}
}
