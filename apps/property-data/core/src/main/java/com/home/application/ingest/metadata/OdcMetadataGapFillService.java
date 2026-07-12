package com.home.application.ingest.metadata;

import java.util.Objects;
import java.util.UUID;

import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

public class OdcMetadataGapFillService {
	private final OdcMetadataGapFillRepository repository;
	private final OdcComplexMetadataResolver resolver;

	public OdcMetadataGapFillService(OdcMetadataGapFillRepository repository, OdcComplexMetadataResolver resolver) {
		this.repository = Objects.requireNonNull(repository);
		this.resolver = Objects.requireNonNull(resolver);
	}

	public OdcMetadataGapFillSummary fill(int maxTargets, Long fromComplexId, long toComplexId, UUID requestId) {
		if (maxTargets <= 0) throw new IllegalArgumentException("maxTargets must be positive");
		if (fromComplexId != null && fromComplexId > toComplexId)
			throw new IllegalArgumentException("fromComplexId must be <= toComplexId");
		Objects.requireNonNull(requestId, "requestId is required");
		if (!resolver.isOdcConfigured()) throw new IllegalStateException("ODC_SERVICE_KEY is required");
		int targets = 0, requests = 0, applied = 0, ambiguous = 0, failed = 0;
		for (OdcMetadataGapFillTarget target : repository.findTargets(maxTargets, fromComplexId, toComplexId, requestId)) {
			targets++;
			OdcMetadataGapFillOutcome outcome;
			if (target.pnuComplexCount() != 1) {
				outcome = repository.recordAmbiguous(target, requestId);
			}
			else {
				requests++;
				ComplexMetadataResolution resolution;
				try {
					resolution = resolver.resolveOdc(target.lookup());
					if (resolution == null) {
						resolution = ComplexMetadataResolution.unavailable("ODC", "ODC resolver returned no result");
					}
				}
				catch (RuntimeException exception) {
					resolution = ComplexMetadataResolution.failed("ODC", ComplexMetadataFailureKind.TRANSIENT,
						redact(exception.getMessage()));
				}
				outcome = repository.saveResolution(target, resolution, requestId);
			}
			if (outcome.projectionApplied()) applied++;
			if (outcome.status() == ComplexMetadataStatus.AMBIGUOUS) ambiguous++;
			if (outcome.status() == ComplexMetadataStatus.FAILED) failed++;
		}
		return new OdcMetadataGapFillSummary(targets, requests, applied, ambiguous, failed);
	}

	private String redact(String value) {
		return value == null ? null : value.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]");
	}
}
