package com.home.infrastructure.external.complex;

import com.home.application.ingest.ComplexMetadataEnrichmentResult;
import com.home.application.ingest.ComplexMetadataEnrichmentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

class ComplexMetadataEnrichmentRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ComplexMetadataEnrichmentRunner.class);

	private final ComplexMetadataEnrichmentService enrichmentService;
	private final int batchSize;

	ComplexMetadataEnrichmentRunner(ComplexMetadataEnrichmentService enrichmentService, int batchSize) {
		this.enrichmentService = enrichmentService;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		ComplexMetadataEnrichmentResult result = enrichmentService.enrichPending(batchSize);
		log.info(
			"complex metadata enrichment completed processed={} resolved={} ambiguous={} unavailable={} failed={}",
			result.processed(),
			result.resolved(),
			result.ambiguous(),
			result.unavailable(),
			result.failed()
		);
	}
}
