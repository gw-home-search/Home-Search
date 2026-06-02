package com.home.application.coordinate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.home.application.complex.ComplexRelationClassification;
import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationRepository;
import com.home.application.complex.ComplexRelationType;

public class ComplexCoordinateExceptionService {

	private static final int BUILDING_DONG_MATCH_CONFIDENCE = 90;
	private static final String BUILDING_FOOTPRINT_SOURCE = "BUILDING_FOOTPRINT";

	private final ComplexCoordinateExceptionRepository repository;
	private final ComplexRelationRepository relationRepository;
	private final ComplexRelationClassifier relationClassifier;
	private final ComplexCoordinateIdentityVerifier identityVerifier;

	public ComplexCoordinateExceptionService(
		ComplexCoordinateExceptionRepository repository,
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier
	) {
		this(
			repository,
			relationRepository,
			relationClassifier,
			ComplexCoordinateIdentityVerifier.trusting()
		);
	}

	public ComplexCoordinateExceptionService(
		ComplexCoordinateExceptionRepository repository,
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier,
		ComplexCoordinateIdentityVerifier identityVerifier
	) {
		this.repository = Objects.requireNonNull(repository);
		this.relationRepository = Objects.requireNonNull(relationRepository);
		this.relationClassifier = Objects.requireNonNull(relationClassifier);
		this.identityVerifier = Objects.requireNonNull(identityVerifier);
	}

	public ComplexCoordinateExceptionResult stageExceptionCases(int limit) {
		if (limit < 1) {
			return ComplexCoordinateExceptionResult.empty();
		}
		ComplexCoordinateExceptionResult result = ComplexCoordinateExceptionResult.empty();
		for (ComplexCoordinateCaseCandidate candidate : repository.findExceptionCaseCandidates(limit)) {
			ComplexRelationClassification classification = relationClassifier.classify(
				relationRepository.findTradeSpansByParcelId(candidate.parcelId())
			);
			ComplexCoordinateCaseStatus status = stageStatusFor(classification.type());
			repository.saveCaseUpdate(new ComplexCoordinateCaseUpdate(
				candidate.parcelId(),
				status,
				classification.type(),
				classification.confidence(),
				classification.reason()
			));
			result = result.plus(status);
		}
		return result;
	}

	public ComplexCoordinateResolutionResult resolveExceptionCase(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		Optional<ComplexCoordinateParcelTargets> targets = repository.findParcelTargets(parcelId);
		if (targets.isEmpty()) {
			return unavailable(parcelId, "parcel targets unavailable");
		}
		return resolveTargets(targets.get());
	}

	private ComplexCoordinateResolutionResult resolveTargets(ComplexCoordinateParcelTargets targets) {
		if (targets.complexes().isEmpty()) {
			return unavailable(targets.parcelId(), "complex targets unavailable");
		}
		List<BuildingFootprintCandidate> footprints = repository.findBuildingFootprintsByPnu(targets.pnu());
		if (footprints.isEmpty()) {
			return unavailable(targets.parcelId(), "building footprint unavailable");
		}
		ArrayList<ResolvedDisplayCoordinate> coordinates = new ArrayList<>();
		for (ComplexCoordinateTarget target : targets.complexes()) {
			ComplexCoordinateResolutionResult blockedByIdentity = blockIfIdentityUnverified(targets, target);
			if (blockedByIdentity != null) {
				return blockedByIdentity;
			}
			ResolvedDisplayCoordinate coordinate = resolveCoordinate(target, footprints);
			if (coordinate == null) {
				String reason = "building dong candidates are ambiguous or unavailable";
				repository.saveCaseUpdate(new ComplexCoordinateCaseUpdate(
					targets.parcelId(),
					ComplexCoordinateCaseStatus.AMBIGUOUS,
					reason
				));
				return new ComplexCoordinateResolutionResult(
					targets.parcelId(),
					ComplexCoordinateCaseStatus.AMBIGUOUS,
					0,
					reason
				);
			}
			coordinates.add(coordinate);
		}
		coordinates.forEach(repository::saveResolvedDisplayCoordinate);
		String reason = "building footprint matched by apt_dong";
		repository.saveCaseUpdate(new ComplexCoordinateCaseUpdate(
			targets.parcelId(),
			ComplexCoordinateCaseStatus.RESOLVED,
			reason
		));
		return new ComplexCoordinateResolutionResult(
			targets.parcelId(),
			ComplexCoordinateCaseStatus.RESOLVED,
			coordinates.size(),
			reason
		);
	}

	private ComplexCoordinateResolutionResult blockIfIdentityUnverified(
		ComplexCoordinateParcelTargets targets,
		ComplexCoordinateTarget target
	) {
		ComplexCoordinateIdentityVerification verification = identityVerifier.verify(targets, target);
		if (verification.status() == ComplexCoordinateIdentityVerificationStatus.CONFIRMED) {
			return null;
		}
		ComplexCoordinateCaseStatus status = switch (verification.status()) {
			case AMBIGUOUS -> ComplexCoordinateCaseStatus.AMBIGUOUS;
			case UNAVAILABLE -> ComplexCoordinateCaseStatus.UNAVAILABLE;
			case FAILED -> ComplexCoordinateCaseStatus.FAILED;
			case CONFIRMED -> throw new IllegalStateException("confirmed identity must not be blocked");
		};
		String reason = "identity verification " + verification.status().name().toLowerCase()
			+ " complexId=" + target.complexId()
			+ (verification.reason() == null ? "" : " reason=" + verification.reason());
		repository.saveCaseUpdate(new ComplexCoordinateCaseUpdate(targets.parcelId(), status, reason));
		return new ComplexCoordinateResolutionResult(targets.parcelId(), status, 0, reason);
	}

	private ResolvedDisplayCoordinate resolveCoordinate(
		ComplexCoordinateTarget target,
		List<BuildingFootprintCandidate> footprints
	) {
		Set<String> targetDongs = normalizeDongTokens(target.aptDongs());
		if (targetDongs.isEmpty()) {
			return null;
		}
		List<BuildingFootprintCandidate> matches = footprints.stream()
			.filter(footprint -> targetDongs.contains(normalizeDongToken(footprint.dongName())))
			.toList();
		if (matches.size() != 1) {
			return null;
		}
		BuildingFootprintCandidate match = matches.get(0);
		return new ResolvedDisplayCoordinate(
			target.complexId(),
			match.id(),
			match.latitude(),
			match.longitude(),
			BUILDING_FOOTPRINT_SOURCE,
			BUILDING_DONG_MATCH_CONFIDENCE,
			"apt_dong matched building dong_name"
		);
	}

	private ComplexCoordinateCaseStatus stageStatusFor(ComplexRelationType type) {
		if (type == ComplexRelationType.CONCURRENT || type == ComplexRelationType.UNKNOWN) {
			return ComplexCoordinateCaseStatus.PENDING;
		}
		return ComplexCoordinateCaseStatus.SKIPPED;
	}

	private ComplexCoordinateResolutionResult unavailable(Long parcelId, String reason) {
		repository.saveCaseUpdate(new ComplexCoordinateCaseUpdate(
			parcelId,
			ComplexCoordinateCaseStatus.UNAVAILABLE,
			reason
		));
		return new ComplexCoordinateResolutionResult(
			parcelId,
			ComplexCoordinateCaseStatus.UNAVAILABLE,
			0,
			reason
		);
	}

	private Set<String> normalizeDongTokens(Set<String> values) {
		Set<String> normalized = new HashSet<>();
		for (String value : values) {
			String token = normalizeDongToken(value);
			if (!token.isBlank()) {
				normalized.add(token);
			}
		}
		return normalized;
	}

	private static String normalizeDongToken(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim().toUpperCase().replaceAll("\\s+", "");
		String digits = normalized.replaceAll("\\D+", "");
		if (!digits.isBlank()) {
			return digits;
		}
		return normalized.replaceAll("동$", "");
	}
}
