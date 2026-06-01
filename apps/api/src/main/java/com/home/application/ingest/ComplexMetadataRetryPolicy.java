package com.home.application.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class ComplexMetadataRetryPolicy {

	private static final Duration[] PARTIAL_BACKOFF = {
		Duration.ofDays(14),
		Duration.ofDays(30),
		Duration.ofDays(90)
	};
	private static final Duration[] SOURCE_MISSING_BACKOFF = {
		Duration.ofDays(30),
		Duration.ofDays(90)
	};
	private static final Duration[] TRANSIENT_FAILURE_BACKOFF = {
		Duration.ofDays(1),
		Duration.ofDays(3),
		Duration.ofDays(7),
		Duration.ofDays(30)
	};

	public Optional<Instant> nextAttemptAt(
		ComplexMetadataStatus status,
		ComplexMetadataFailureKind failureKind,
		int attemptNo,
		Instant now
	) {
		Objects.requireNonNull(status, "status is required");
		Objects.requireNonNull(now, "now is required");
		if (attemptNo <= 0) {
			return Optional.empty();
		}
		return switch (status) {
			case PARTIAL -> next(now, PARTIAL_BACKOFF, attemptNo);
			case UNAVAILABLE -> unavailableNextAttempt(failureKind, attemptNo, now);
			case FAILED -> failedNextAttempt(failureKind, attemptNo, now);
			case PENDING, RESOLVED, AMBIGUOUS -> Optional.empty();
		};
	}

	private Optional<Instant> unavailableNextAttempt(
		ComplexMetadataFailureKind failureKind,
		int attemptNo,
		Instant now
	) {
		if (failureKind == ComplexMetadataFailureKind.SOURCE_MISSING) {
			return next(now, SOURCE_MISSING_BACKOFF, attemptNo);
		}
		return Optional.empty();
	}

	private Optional<Instant> failedNextAttempt(
		ComplexMetadataFailureKind failureKind,
		int attemptNo,
		Instant now
	) {
		if (failureKind == ComplexMetadataFailureKind.TRANSIENT) {
			return next(now, TRANSIENT_FAILURE_BACKOFF, attemptNo);
		}
		return Optional.empty();
	}

	private Optional<Instant> next(Instant now, Duration[] backoff, int attemptNo) {
		int index = attemptNo - 1;
		if (index >= backoff.length) {
			return Optional.empty();
		}
		return Optional.of(now.plus(backoff[index]));
	}
}
