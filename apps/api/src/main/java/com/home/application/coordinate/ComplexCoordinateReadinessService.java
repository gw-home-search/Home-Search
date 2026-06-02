package com.home.application.coordinate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class ComplexCoordinateReadinessService {

	private final ComplexCoordinateExceptionService coordinateExceptionService;
	private final ComplexCoordinateReadinessRepository readinessRepository;
	private final ComplexDisplayCoordinateProjectionService projectionService;
	private final int retryLimit;
	private final Duration retryAfter;

	public ComplexCoordinateReadinessService(
		ComplexCoordinateExceptionService coordinateExceptionService,
		ComplexCoordinateReadinessRepository readinessRepository,
		ComplexDisplayCoordinateProjectionService projectionService
	) {
		this(coordinateExceptionService, readinessRepository, projectionService, 0, Duration.ZERO);
	}

	public ComplexCoordinateReadinessService(
		ComplexCoordinateExceptionService coordinateExceptionService,
		ComplexCoordinateReadinessRepository readinessRepository,
		ComplexDisplayCoordinateProjectionService projectionService,
		int retryLimit,
		Duration retryAfter
	) {
		this.coordinateExceptionService = Objects.requireNonNull(coordinateExceptionService);
		this.readinessRepository = Objects.requireNonNull(readinessRepository);
		this.projectionService = Objects.requireNonNull(projectionService);
		if (retryLimit < 0) {
			throw new IllegalArgumentException("retryLimit must be non-negative");
		}
		if (retryAfter == null || retryAfter.isNegative()) {
			throw new IllegalArgumentException("retryAfter must be non-negative");
		}
		this.retryLimit = retryLimit;
		this.retryAfter = retryAfter;
	}

	public ComplexCoordinateReadinessResult prepare(int stageLimit, int resolveLimit, int projectLimit) {
		ComplexCoordinateExceptionResult stageResult = stageLimit > 0
			? coordinateExceptionService.stageExceptionCases(stageLimit)
			: ComplexCoordinateExceptionResult.empty();
		ResolutionSummary resolutionSummary = resolvePending(resolveLimit);
		RetryOutcome retryOutcome = retryFailed();
		ResolutionSummary combined = resolutionSummary.plus(retryOutcome.summary());
		ComplexDisplayCoordinateProjectionResult projectionResult = projectLimit > 0
			? projectionService.project(projectLimit)
			: ComplexDisplayCoordinateProjectionResult.empty();
		return new ComplexCoordinateReadinessResult(
			stageResult.processed(),
			stageResult.pending(),
			stageResult.skipped(),
			combined.resolved(),
			combined.ambiguous(),
			combined.unavailable(),
			combined.failed(),
			retryOutcome.retried(),
			projectionResult.buildingFootprint(),
			projectionResult.parcelFallback(),
			projectionResult.skipped(),
			projectionResult.missing()
		);
	}

	private RetryOutcome retryFailed() {
		if (retryLimit < 1) {
			return RetryOutcome.empty();
		}
		Instant retryBefore = Instant.now().minus(retryAfter);
		ResolutionSummary summary = ResolutionSummary.empty();
		int retried = 0;
		for (Long parcelId : readinessRepository.findRetryableFailedCaseParcelIds(retryLimit, retryBefore)) {
			retried++;
			try {
				ComplexCoordinateResolutionResult result = coordinateExceptionService.resolveExceptionCase(parcelId);
				summary = summary.plus(result.status());
			} catch (RuntimeException exception) {
				readinessRepository.markCaseFailed(parcelId, failureReason(exception));
				summary = summary.plus(ComplexCoordinateCaseStatus.FAILED);
			}
		}
		return new RetryOutcome(summary, retried);
	}

	private ResolutionSummary resolvePending(int resolveLimit) {
		if (resolveLimit < 1) {
			return ResolutionSummary.empty();
		}
		ResolutionSummary summary = ResolutionSummary.empty();
		for (Long parcelId : readinessRepository.findPendingCaseParcelIds(resolveLimit)) {
			try {
				ComplexCoordinateResolutionResult result = coordinateExceptionService.resolveExceptionCase(parcelId);
				summary = summary.plus(result.status());
			} catch (RuntimeException exception) {
				readinessRepository.markCaseFailed(parcelId, failureReason(exception));
				summary = summary.plus(ComplexCoordinateCaseStatus.FAILED);
			}
		}
		return summary;
	}

	private String failureReason(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return exception.getClass().getSimpleName();
		}
		return message;
	}

	private record ResolutionSummary(
		int resolved,
		int ambiguous,
		int unavailable,
		int failed
	) {

		private static ResolutionSummary empty() {
			return new ResolutionSummary(0, 0, 0, 0);
		}

		private ResolutionSummary plus(ComplexCoordinateCaseStatus status) {
			return new ResolutionSummary(
				resolved + (status == ComplexCoordinateCaseStatus.RESOLVED ? 1 : 0),
				ambiguous + (status == ComplexCoordinateCaseStatus.AMBIGUOUS ? 1 : 0),
				unavailable + (status == ComplexCoordinateCaseStatus.UNAVAILABLE ? 1 : 0),
				failed + (status == ComplexCoordinateCaseStatus.FAILED ? 1 : 0)
			);
		}

		private ResolutionSummary plus(ResolutionSummary other) {
			return new ResolutionSummary(
				resolved + other.resolved,
				ambiguous + other.ambiguous,
				unavailable + other.unavailable,
				failed + other.failed
			);
		}
	}

	private record RetryOutcome(ResolutionSummary summary, int retried) {

		private static RetryOutcome empty() {
			return new RetryOutcome(ResolutionSummary.empty(), 0);
		}
	}
}
