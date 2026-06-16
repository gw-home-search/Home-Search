package com.home.infrastructure.scheduling.rtms;

enum RtmsMonthlyRefreshRunStatus {

	COMPLETED("COMPLETED", false),
	PARTIAL("PARTIAL", true),
	FAILED("FAILED", true);

	private final String storedValue;
	private final boolean failure;

	RtmsMonthlyRefreshRunStatus(String storedValue, boolean failure) {
		this.storedValue = storedValue;
		this.failure = failure;
	}

	String storedValue() {
		return storedValue;
	}

	String failureReason(String failureReason) {
		return failure ? failureReason : null;
	}
}
