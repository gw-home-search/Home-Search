package com.home.application.ingest.matching;

public record ComplexMasterBootstrapResult(
	boolean attempted,
	boolean changed,
	String failureReason
) {

	public ComplexMasterBootstrapResult {
		failureReason = hasText(failureReason) ? failureReason.trim() : null;
	}

	public static ComplexMasterBootstrapResult noop() {
		return new ComplexMasterBootstrapResult(false, false, null);
	}

	public static ComplexMasterBootstrapResult alreadyPresent() {
		return new ComplexMasterBootstrapResult(true, false, null);
	}

	public static ComplexMasterBootstrapResult bootstrapped() {
		return new ComplexMasterBootstrapResult(true, true, null);
	}

	public static ComplexMasterBootstrapResult skipped(String failureReason) {
		return new ComplexMasterBootstrapResult(true, false, failureReason);
	}

	public boolean hasFailureReason() {
		return failureReason != null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
