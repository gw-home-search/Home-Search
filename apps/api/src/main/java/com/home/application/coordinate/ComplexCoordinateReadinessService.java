package com.home.application.coordinate;

import java.util.Objects;

public class ComplexCoordinateReadinessService {

	private final ComplexCoordinateExceptionService coordinateExceptionService;
	private final ComplexCoordinateReadinessRepository readinessRepository;
	private final ComplexDisplayCoordinateProjectionService projectionService;

	public ComplexCoordinateReadinessService(
		ComplexCoordinateExceptionService coordinateExceptionService,
		ComplexCoordinateReadinessRepository readinessRepository,
		ComplexDisplayCoordinateProjectionService projectionService
	) {
		this.coordinateExceptionService = Objects.requireNonNull(coordinateExceptionService);
		this.readinessRepository = Objects.requireNonNull(readinessRepository);
		this.projectionService = Objects.requireNonNull(projectionService);
	}

	public ComplexCoordinateReadinessResult prepare(int stageLimit, int resolveLimit, int projectLimit) {
		ComplexCoordinateExceptionResult stageResult = stageLimit > 0
			? coordinateExceptionService.stageExceptionCases(stageLimit)
			: ComplexCoordinateExceptionResult.empty();
		ResolutionSummary resolutionSummary = resolvePending(resolveLimit);
		ComplexDisplayCoordinateProjectionResult projectionResult = projectLimit > 0
			? projectionService.project(projectLimit)
			: ComplexDisplayCoordinateProjectionResult.empty();
		return new ComplexCoordinateReadinessResult(
			stageResult.processed(),
			stageResult.pending(),
			stageResult.skipped(),
			resolutionSummary.resolved(),
			resolutionSummary.ambiguous(),
			resolutionSummary.unavailable(),
			resolutionSummary.failed(),
			projectionResult.buildingFootprint(),
			projectionResult.parcelFallback(),
			projectionResult.skipped(),
			projectionResult.missing()
		);
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
	}
}
