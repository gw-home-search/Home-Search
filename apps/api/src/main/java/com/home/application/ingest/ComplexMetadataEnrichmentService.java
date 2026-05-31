package com.home.application.ingest;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComplexMetadataEnrichmentService {

	private static final Logger log = LoggerFactory.getLogger(ComplexMetadataEnrichmentService.class);

	private final ComplexMetadataEnrichmentRepository repository;
	private final ComplexMetadataEnrichmentClient client;

	public ComplexMetadataEnrichmentService(
		ComplexMetadataEnrichmentRepository repository,
		ComplexMetadataEnrichmentClient client
	) {
		this.repository = Objects.requireNonNull(repository);
		this.client = Objects.requireNonNull(client);
	}

	public ComplexMetadataEnrichmentResult enrichPending(int limit) {
		if (limit <= 0) {
			return ComplexMetadataEnrichmentResult.empty();
		}
		ComplexMetadataEnrichmentResult result = ComplexMetadataEnrichmentResult.empty();
		for (ComplexMetadataLookup lookup : repository.findPending(limit)) {
			result = result.plus(enrichOne(lookup).status());
		}
		return result;
	}

	private ComplexMetadataResolution enrichOne(ComplexMetadataLookup lookup) {
		try {
			ComplexMetadataResolution resolution = client.resolve(lookup);
			if (resolution == null) {
				resolution = ComplexMetadataResolution.unavailable(null, "complex metadata resolver returned no result");
			}
			repository.saveResolution(lookup.complexId(), resolution);
			return resolution;
		}
		catch (RuntimeException exception) {
			ComplexMetadataResolution failed = ComplexMetadataResolution.failed(null, redactSensitive(exception.getMessage()));
			markFailed(lookup, failed, exception);
			return failed;
		}
	}

	private void markFailed(ComplexMetadataLookup lookup, ComplexMetadataResolution failed, RuntimeException exception) {
		try {
			repository.saveResolution(lookup.complexId(), failed);
		}
		catch (RuntimeException saveException) {
			log.warn(
				"complex metadata enrichment failure status save failed complexId={}",
				lookup.complexId(),
				saveException
			);
		}
		log.warn("complex metadata enrichment failed complexId={}", lookup.complexId(), exception);
	}

	private String redactSensitive(String message) {
		if (message == null) {
			return null;
		}
		return message.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]");
	}
}
