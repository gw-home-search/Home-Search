package com.home.application.ingest.metadata;

import java.util.Objects;

public record ComplexMetadataResolution(
	ComplexMetadataStatus status,
	ComplexMetadata metadata,
	String source,
	ComplexMetadataFailureKind failureKind,
	String failureReason
) {

	public ComplexMetadataResolution {
		Objects.requireNonNull(status, "status is required");
		source = trimToNull(source);
		failureReason = trimToNull(failureReason);
		if (status.requiresMetadataPayload() && metadata == null) {
			throw new IllegalArgumentException("resolved or partial metadata is required");
		}
		if (status.requiresCompleteCriticalFields() && !metadata.hasAllCriticalFields()) {
			throw new IllegalArgumentException("resolved metadata must include critical fields");
		}
	}

	public static ComplexMetadataResolution resolved(String source, ComplexMetadata metadata) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.RESOLVED, metadata, source, null, null);
	}

	public static ComplexMetadataResolution partial(String source, ComplexMetadata metadata) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.PARTIAL, metadata, source, null, null);
	}

	public static ComplexMetadataResolution classify(String source, ComplexMetadata metadata) {
		if (metadata != null && metadata.hasAllCriticalFields()) {
			return resolved(source, metadata);
		}
		return partial(source, metadata);
	}

	public static ComplexMetadataResolution ambiguous(String source, String reason) {
		return ambiguous(source, ComplexMetadataFailureKind.AMBIGUOUS, reason);
	}

	public static ComplexMetadataResolution ambiguous(
		String source,
		ComplexMetadataFailureKind failureKind,
		String reason
	) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.AMBIGUOUS, null, source, failureKind, reason);
	}

	public static ComplexMetadataResolution unavailable(String source, String reason) {
		return unavailable(source, ComplexMetadataFailureKind.SOURCE_MISSING, reason);
	}

	public static ComplexMetadataResolution unavailable(
		String source,
		ComplexMetadataFailureKind failureKind,
		String reason
	) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.UNAVAILABLE, null, source, failureKind, reason);
	}

	public static ComplexMetadataResolution failed(String source, String reason) {
		return failed(source, ComplexMetadataFailureKind.TRANSIENT, reason);
	}

	public static ComplexMetadataResolution failed(
		String source,
		ComplexMetadataFailureKind failureKind,
		String reason
	) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.FAILED, null, source, failureKind, reason);
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
