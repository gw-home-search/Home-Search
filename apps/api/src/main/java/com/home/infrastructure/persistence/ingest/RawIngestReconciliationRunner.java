package com.home.infrastructure.persistence.ingest;

import com.home.application.ingest.RawIngestReconciliationResult;
import com.home.application.ingest.RawIngestReconciliationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

class RawIngestReconciliationRunner implements ApplicationRunner {

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
}
