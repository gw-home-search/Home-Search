package com.home.infrastructure.external.complex;

import com.home.application.ingest.ComplexMetadataEnrichmentResult;
import com.home.application.ingest.ComplexMetadataEnrichmentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class ComplexMetadataEnrichmentScheduler {

	private static final Logger log = LoggerFactory.getLogger(ComplexMetadataEnrichmentScheduler.class);

	private final ComplexMetadataEnrichmentService enrichmentService;
	private final int batchSize;

	ComplexMetadataEnrichmentScheduler(ComplexMetadataEnrichmentService enrichmentService, int batchSize) {
		this.enrichmentService = enrichmentService;
		this.batchSize = batchSize;
	}

	@Scheduled(
		initialDelayString = "${complex.metadata.enrich.scheduler.initial-delay-millis:60000}",
		fixedDelayString = "${complex.metadata.enrich.scheduler.fixed-delay-millis:3600000}"
	)
	void runDue() {
		ComplexMetadataEnrichmentResult result = enrichmentService.enrichPending(batchSize);
		log.info(
			"complex metadata enrichment scheduled run completed processed={} resolved={} partial={} ambiguous={} unavailable={} failed={}",
			result.processed(),
			result.resolved(),
			result.partial(),
			result.ambiguous(),
			result.unavailable(),
			result.failed()
		);
	}
}
