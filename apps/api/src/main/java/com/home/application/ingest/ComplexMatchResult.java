package com.home.application.ingest;

public record ComplexMatchResult(
	Long complexId,
	String complexPk,
	String matchPath,
	String failureReason
) {

	public static ComplexMatchResult matched(Long complexId, String complexPk, String matchPath) {
		if (complexId == null) {
			throw new IllegalArgumentException("complexId is required");
		}
		if (!hasText(complexPk)) {
			throw new IllegalArgumentException("complexPk is required");
		}
		if (!hasText(matchPath)) {
			throw new IllegalArgumentException("matchPath is required");
		}
		return new ComplexMatchResult(complexId, complexPk.trim(), matchPath.trim(), null);
	}

	public static ComplexMatchResult failed(String failureReason) {
		return new ComplexMatchResult(null, null, null,
			hasText(failureReason) ? failureReason.trim() : "complex match failed");
	}

	public boolean matched() {
		return complexId != null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
