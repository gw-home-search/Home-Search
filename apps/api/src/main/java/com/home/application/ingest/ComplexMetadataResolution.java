package com.home.application.ingest;

import java.util.Objects;

public record ComplexMetadataResolution(
	ComplexMetadataStatus status,
	ComplexMetadata metadata,
	String source,
	String failureReason
) {

	public ComplexMetadataResolution {
		Objects.requireNonNull(status, "status is required");
		source = trimToNull(source);
		failureReason = trimToNull(failureReason);
		if (status == ComplexMetadataStatus.RESOLVED && metadata == null) {
			throw new IllegalArgumentException("resolved metadata is required");
		}
	}

	public static ComplexMetadataResolution resolved(String source, ComplexMetadata metadata) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.RESOLVED, metadata, source, null);
	}

	public static ComplexMetadataResolution ambiguous(String source, String reason) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.AMBIGUOUS, null, source, reason);
	}

	public static ComplexMetadataResolution unavailable(String source, String reason) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.UNAVAILABLE, null, source, reason);
	}

	public static ComplexMetadataResolution failed(String source, String reason) {
		return new ComplexMetadataResolution(ComplexMetadataStatus.FAILED, null, source, reason);
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
