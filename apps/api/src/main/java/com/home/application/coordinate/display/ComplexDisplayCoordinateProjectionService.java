package com.home.application.coordinate.display;

import java.util.Objects;

import com.home.domain.coordinate.CoordinateSource;

public class ComplexDisplayCoordinateProjectionService {

	private static final int DEFAULT_BUILDING_CONFIDENCE = 90;
	private static final int SINGLE_COMPLEX_FALLBACK_CONFIDENCE = 70;
	private static final int MULTI_COMPLEX_FALLBACK_CONFIDENCE = 50;
	private static final int UNRESOLVED_CASE_FALLBACK_CONFIDENCE = 40;

	private final ComplexDisplayCoordinateProjectionRepository repository;

	public ComplexDisplayCoordinateProjectionService(ComplexDisplayCoordinateProjectionRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public ComplexDisplayCoordinateProjectionResult project(int limit) {
		if (limit < 1) {
			return ComplexDisplayCoordinateProjectionResult.empty();
		}
		ComplexDisplayCoordinateProjectionResult result = ComplexDisplayCoordinateProjectionResult.empty();
		for (ComplexDisplayCoordinateProjectionTarget target : repository.findProjectionTargets(limit)) {
			result = projectOne(target, result);
		}
		return result;
	}

	private ComplexDisplayCoordinateProjectionResult projectOne(
		ComplexDisplayCoordinateProjectionTarget target,
		ComplexDisplayCoordinateProjectionResult result
	) {
		if (target.hasExistingBuildingFootprintCoordinate()) {
			return result.plusSkipped();
		}
		if (target.hasResolvedBuildingCoordinate()) {
			repository.saveDisplayCoordinate(new ComplexDisplayCoordinateCommand(
				target.complexId(),
				target.resolvedBuildingFootprintId(),
				target.resolvedLatitude(),
				target.resolvedLongitude(),
				CoordinateSource.BUILDING_FOOTPRINT.storedValue(),
				buildingConfidence(target),
				buildingReason(target)
			));
			return result.plusBuildingFootprint();
		}
		if (!target.hasParcelCoordinate()) {
			return result.plusMissing();
		}
		repository.saveDisplayCoordinate(new ComplexDisplayCoordinateCommand(
			target.complexId(),
			null,
			target.parcelLatitude(),
			target.parcelLongitude(),
			CoordinateSource.PARCEL_FALLBACK.storedValue(),
			fallbackConfidence(target),
			fallbackReason(target)
		));
		return result.plusParcelFallback();
	}

	private int buildingConfidence(ComplexDisplayCoordinateProjectionTarget target) {
		if (target.resolvedConfidence() == null) {
			return DEFAULT_BUILDING_CONFIDENCE;
		}
		return target.resolvedConfidence();
	}

	private String buildingReason(ComplexDisplayCoordinateProjectionTarget target) {
		if (target.resolvedReason() == null || target.resolvedReason().isBlank()) {
			return "resolved building footprint coordinate";
		}
		return target.resolvedReason();
	}

	private int fallbackConfidence(ComplexDisplayCoordinateProjectionTarget target) {
		if (target.parcelComplexCount() <= 1) {
			return SINGLE_COMPLEX_FALLBACK_CONFIDENCE;
		}
		if (target.coordinateCaseStatus() != null
			&& target.coordinateCaseStatus().usesUnresolvedFallbackConfidence()) {
			return UNRESOLVED_CASE_FALLBACK_CONFIDENCE;
		}
		return MULTI_COMPLEX_FALLBACK_CONFIDENCE;
	}

	private String fallbackReason(ComplexDisplayCoordinateProjectionTarget target) {
		if (target.parcelComplexCount() <= 1) {
			return "single complex parcel fallback coordinate";
		}
		return "multi complex parcel fallback coordinate";
	}
}
