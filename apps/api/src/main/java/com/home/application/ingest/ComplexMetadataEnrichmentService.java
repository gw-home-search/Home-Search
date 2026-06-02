package com.home.application.ingest;

import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComplexMetadataEnrichmentService {

	private static final Logger log = LoggerFactory.getLogger(ComplexMetadataEnrichmentService.class);

	private final ComplexMetadataEnrichmentRepository repository;
	private final ComplexMetadataEnrichmentClient client;
	private final ComplexMetadataRetryPolicy retryPolicy;

	public ComplexMetadataEnrichmentService(
		ComplexMetadataEnrichmentRepository repository,
		ComplexMetadataEnrichmentClient client
	) {
		this(repository, client, new ComplexMetadataRetryPolicy());
	}

	ComplexMetadataEnrichmentService(
		ComplexMetadataEnrichmentRepository repository,
		ComplexMetadataEnrichmentClient client,
		ComplexMetadataRetryPolicy retryPolicy
	) {
		this.repository = Objects.requireNonNull(repository);
		this.client = Objects.requireNonNull(client);
		this.retryPolicy = Objects.requireNonNull(retryPolicy);
	}

	public ComplexMetadataEnrichmentResult enrichPending(int limit) {
		if (limit <= 0) {
			return ComplexMetadataEnrichmentResult.empty();
		}
		if (!client.isConfigured()) {
			log.warn("complex metadata enrichment skipped because external client is not configured");
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
			repository.saveResolution(lookup.complexId(), resolution, nextAttemptAt(lookup, resolution));
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
			repository.saveResolution(lookup.complexId(), failed, nextAttemptAt(lookup, failed));
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

	private Instant nextAttemptAt(ComplexMetadataLookup lookup, ComplexMetadataResolution resolution) {
		int attemptNo = lookup.attempts() + 1;
		return retryPolicy.nextAttemptAt(
			resolution.status(),
			resolution.failureKind(),
			attemptNo,
			Instant.now()
		).orElse(null);
	}

	private String redactSensitive(String message) {
		if (message == null) {
			return null;
		}
		return message.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]");
	}
}
