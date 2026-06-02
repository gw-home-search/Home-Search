package com.home.application.coordinate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	private final BuildingFootprintSource buildingFootprintSource;

	public ComplexCoordinateExceptionService(
		ComplexCoordinateExceptionRepository repository,
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier
	) {
		this(
			repository,
			relationRepository,
			relationClassifier,
			ComplexCoordinateIdentityVerifier.trusting(),
			BuildingFootprintSource.unavailable()
		);
	}

	public ComplexCoordinateExceptionService(
		ComplexCoordinateExceptionRepository repository,
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier,
		ComplexCoordinateIdentityVerifier identityVerifier
	) {
		this(
			repository,
			relationRepository,
			relationClassifier,
			identityVerifier,
			BuildingFootprintSource.unavailable()
		);
	}

	public ComplexCoordinateExceptionService(
		ComplexCoordinateExceptionRepository repository,
		ComplexRelationRepository relationRepository,
		ComplexRelationClassifier relationClassifier,
		ComplexCoordinateIdentityVerifier identityVerifier,
		BuildingFootprintSource buildingFootprintSource
	) {
		this.repository = Objects.requireNonNull(repository);
		this.relationRepository = Objects.requireNonNull(relationRepository);
		this.relationClassifier = Objects.requireNonNull(relationClassifier);
		this.identityVerifier = Objects.requireNonNull(identityVerifier);
		this.buildingFootprintSource = Objects.requireNonNull(buildingFootprintSource);
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
		List<BuildingFootprintCandidate> footprints = findOrFetchBuildingFootprints(targets.pnu());
		if (footprints.isEmpty()) {
			return unavailable(targets.parcelId(), "building footprint unavailable");
		}
		ArrayList<ResolvedDisplayCoordinate> coordinates = new ArrayList<>();
		Set<Long> assignedFootprintIds = new HashSet<>();
		for (ComplexCoordinateTarget target : targets.complexes()) {
			ComplexCoordinateResolutionResult blockedByIdentity = blockIfIdentityUnverified(targets, target);
			if (blockedByIdentity != null) {
				return blockedByIdentity;
			}
			ResolvedCoordinateMatch coordinate = resolveCoordinate(target, footprints);
			if (coordinate == null || overlaps(assignedFootprintIds, coordinate.footprintIds())) {
				String reason = coordinate == null
					? "building dong candidates are ambiguous or unavailable"
					: "building dong candidates overlap across complexes";
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
			assignedFootprintIds.addAll(coordinate.footprintIds());
			coordinates.add(coordinate.coordinate());
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

	private List<BuildingFootprintCandidate> findOrFetchBuildingFootprints(String pnu) {
		List<BuildingFootprintCandidate> footprints = repository.findBuildingFootprintsByPnu(pnu);
		if (!footprints.isEmpty()) {
			return footprints;
		}
		List<BuildingFootprintImportCandidate> imported = buildingFootprintSource.fetchByPnu(pnu);
		if (imported.isEmpty()) {
			return footprints;
		}
		repository.saveBuildingFootprints(imported);
		return repository.findBuildingFootprintsByPnu(pnu);
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

	private ResolvedCoordinateMatch resolveCoordinate(
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
		if (matches.isEmpty() || hasDuplicateDongToken(matches)) {
			return null;
		}
		return new ResolvedCoordinateMatch(
			new ResolvedDisplayCoordinate(
				target.complexId(),
				representativeBuildingFootprintId(matches),
				averageLatitude(matches),
				averageLongitude(matches),
				BUILDING_FOOTPRINT_SOURCE,
				BUILDING_DONG_MATCH_CONFIDENCE,
				matches.size() == 1
					? "apt_dong matched building dong_name"
					: "apt_dong matched building dong_name aggregate footprint_count=" + matches.size()
			),
			matches.stream().map(BuildingFootprintCandidate::id).collect(java.util.stream.Collectors.toSet())
		);
	}

	private boolean overlaps(Set<Long> assignedFootprintIds, Set<Long> candidateFootprintIds) {
		for (Long footprintId : candidateFootprintIds) {
			if (assignedFootprintIds.contains(footprintId)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasDuplicateDongToken(List<BuildingFootprintCandidate> matches) {
		Map<String, Integer> counts = new HashMap<>();
		for (BuildingFootprintCandidate match : matches) {
			String token = normalizeDongToken(match.dongName());
			if (token.isBlank()) {
				return true;
			}
			counts.put(token, counts.getOrDefault(token, 0) + 1);
			if (counts.get(token) > 1) {
				return true;
			}
		}
		return false;
	}

	private Long representativeBuildingFootprintId(List<BuildingFootprintCandidate> matches) {
		return matches.stream()
			.map(BuildingFootprintCandidate::id)
			.min(Long::compareTo)
			.orElseThrow();
	}

	private BigDecimal averageLatitude(List<BuildingFootprintCandidate> matches) {
		return average(matches.stream().map(BuildingFootprintCandidate::latitude).toList());
	}

	private BigDecimal averageLongitude(List<BuildingFootprintCandidate> matches) {
		return average(matches.stream().map(BuildingFootprintCandidate::longitude).toList());
	}

	private BigDecimal average(List<BigDecimal> values) {
		BigDecimal sum = BigDecimal.ZERO;
		for (BigDecimal value : values) {
			sum = sum.add(value);
		}
		return sum.divide(BigDecimal.valueOf(values.size()), 7, RoundingMode.HALF_UP);
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

	private record ResolvedCoordinateMatch(
		ResolvedDisplayCoordinate coordinate,
		Set<Long> footprintIds
	) {
	}
}
