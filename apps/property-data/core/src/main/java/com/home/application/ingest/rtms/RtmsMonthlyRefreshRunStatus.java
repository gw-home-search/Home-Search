package com.home.application.ingest.rtms;

public enum RtmsMonthlyRefreshRunStatus {

	COMPLETED("COMPLETED", false),
	PARTIAL("PARTIAL", true),
	FAILED("FAILED", true);

	private final String storedValue;
	private final boolean failure;

	RtmsMonthlyRefreshRunStatus(String storedValue, boolean failure) {
		this.storedValue = storedValue;
		this.failure = failure;
	}

	public String storedValue() {
		return storedValue;
	}

	public String failureReason(String failureReason) {
		return failure ? failureReason : null;
	}
}
