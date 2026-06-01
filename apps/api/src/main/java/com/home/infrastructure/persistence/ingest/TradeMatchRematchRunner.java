package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.TradeMatchRematchResult;
import com.home.application.ingest.TradeMatchRematchService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class TradeMatchRematchRunner implements ApplicationRunner, Ordered {

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

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.TRADE_MATCH_REMATCH;
	}
}
