package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.reconciliation.RawIngestReconciliationResult;
import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class RawIngestReconciliationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RawIngestReconciliationRunner.class);

	private final RawIngestReconciliationService service;
	private final int batchSize;

	RawIngestReconciliationRunner(RawIngestReconciliationService service, int batchSize) {
		this.service = service;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		RawIngestReconciliationResult result = service.reconcileReceived(batchSize);
		log.info("raw ingest reconciliation completed processed={} normalized={}", result.processed(), result.normalized());
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.RAW_INGEST_RECONCILIATION;
	}
}
