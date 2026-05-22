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

	RtmsOneShotIngestApplicationRunner(
		RtmsOneShotTradeIngestRunner runner,
		RtmsOneShotIngestProperties properties
	) {
		this.runner = runner;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}

		RtmsApartmentTradeRequest request = properties.request();
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
