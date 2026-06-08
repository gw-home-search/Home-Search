package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.matching.TradeMatchRematchResult;
import com.home.application.ingest.matching.TradeMatchRematchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

class TradeMatchRematchRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TradeMatchRematchRunner.class);

	private final TradeMatchRematchService service;
	private final int batchSize;

	TradeMatchRematchRunner(TradeMatchRematchService service, int batchSize) {
		this.service = service;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		TradeMatchRematchResult result = service.rematchHeld(batchSize);
		log.info(
			"trade match rematch completed processed={} normalized={} duplicate={} stillFailed={} parseFailed={} skipped={}",
			result.processed(),
			result.normalized(),
			result.duplicate(),
			result.stillFailed(),
			result.parseFailed(),
			result.skipped()
		);
	}
}
