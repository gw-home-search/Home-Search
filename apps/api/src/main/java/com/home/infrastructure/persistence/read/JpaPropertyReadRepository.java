package com.home.infrastructure.persistence.read;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.home.application.read.ParcelDetailResult;
import com.home.application.read.PropertyReadRepository;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;

import jakarta.persistence.EntityManager;

public class JpaPropertyReadRepository implements PropertyReadRepository {

	private final EntityManager entityManager;

	public JpaPropertyReadRepository(EntityManager entityManager) {
		this.entityManager = Objects.requireNonNull(entityManager);
	}

	@Override
	public List<SearchComplexResult> searchComplexes(String query) {
		String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
		String normalizedQuery = normalizeName(query);
		String normalizedPattern = normalizedQuery.isBlank() ? null : "%" + normalizedQuery + "%";
		return entityManager.createQuery("""
			SELECT c, p, displayCoordinate
			FROM ComplexReadEntity c
			JOIN ParcelReadEntity p ON p.id = c.parcelId
			LEFT JOIN ComplexDisplayCoordinateReadEntity displayCoordinate ON displayCoordinate.complexId = c.id
			WHERE lower(c.name) LIKE :pattern
			   OR lower(COALESCE(c.tradeName, '')) LIKE :pattern
			   OR lower(COALESCE(p.address, '')) LIKE :pattern
			   OR EXISTS (
			       SELECT 1
			       FROM ComplexNameAliasReadEntity nameAlias
			       WHERE nameAlias.complexId = c.id
			         AND (
			             lower(nameAlias.aliasName) LIKE :pattern
			             OR (:normalizedPattern IS NOT NULL AND nameAlias.normalizedName LIKE :normalizedPattern)
			         )
			   )
			ORDER BY
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    c.id
			""", Object[].class)
			.setParameter("pattern", pattern)
			.setParameter("normalizedPattern", normalizedPattern)
			.setMaxResults(20)
			.getResultList()
			.stream()
			.map(this::mapSearchComplex)
			.toList();
	}

	@Override
	public List<RegionSummaryResult> findRootRegions() {
		return entityManager.createQuery("""
			SELECT r
			FROM RegionReadEntity r
			WHERE r.parentId IS NULL
			ORDER BY r.id
			""", RegionReadEntity.class)
			.getResultList()
			.stream()
			.map(this::mapRegionSummary)
			.toList();
	}

	@Override
	public Optional<RegionDetailResult> findRegionDetail(Long regionId) {
		RegionReadEntity region = entityManager.find(RegionReadEntity.class, regionId);
		if (region == null) {
			return Optional.empty();
		}
		List<RegionSummaryResult> children = entityManager.createQuery("""
			SELECT r
			FROM RegionReadEntity r
			WHERE r.parentId = :regionId
			ORDER BY r.id
			""", RegionReadEntity.class)
			.setParameter("regionId", regionId)
			.getResultList()
			.stream()
			.map(this::mapRegionSummary)
			.toList();
		return Optional.of(new RegionDetailResult(
			region.id(),
			region.name(),
			region.centerLat(),
			region.centerLng(),
			children
		));
	}

	@Override
	public Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
		ParcelReadEntity parcel = entityManager.find(ParcelReadEntity.class, parcelId);
		if (parcel == null) {
			return Optional.empty();
		}
		List<DetailCandidate> candidates = findDetailCandidates(parcelId, complexId);
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		DetailCandidate candidate = complexId == null
			? representativeCandidate(parcelId, candidates)
			: candidates.get(0);
		return Optional.of(mapParcelDetail(parcel, candidate));
	}

	@Override
	public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId) {
		if (!hasComplexParent(parcelId, complexId)) {
			return Optional.empty();
		}
		List<TradeResult> trades = entityManager.createQuery("""
			SELECT new com.home.application.read.TradeResult(
			    t.id,
			    t.dealDate,
			    t.exclArea,
			    t.dealAmount,
			    t.aptDong,
			    t.floor
			)
			FROM TradeReadEntity t
			JOIN ComplexReadEntity c ON c.id = t.complexId
			WHERE c.parcelId = :parcelId
			  AND (:complexId IS NULL OR c.id = :complexId)
			  AND t.deletedAt IS NULL
			ORDER BY t.dealDate DESC, t.id DESC
			""", TradeResult.class)
			.setParameter("parcelId", parcelId)
			.setParameter("complexId", complexId)
			.getResultList();
		return Optional.of(new TradeListResult(parcelId, complexId, trades));
	}

	private SearchComplexResult mapSearchComplex(Object[] row) {
		ComplexReadEntity complex = (ComplexReadEntity) row[0];
		ParcelReadEntity parcel = (ParcelReadEntity) row[1];
		ComplexDisplayCoordinateReadEntity displayCoordinate = (ComplexDisplayCoordinateReadEntity) row[2];
		return new SearchComplexResult(
			complex.id(),
			complex.displayName(),
			parcel.id(),
			latitude(displayCoordinate, parcel),
			longitude(displayCoordinate, parcel),
			parcel.address()
		);
	}

	private RegionSummaryResult mapRegionSummary(RegionReadEntity region) {
		return new RegionSummaryResult(region.id(), region.name());
	}

	private ParcelDetailResult mapParcelDetail(ParcelReadEntity parcel, DetailCandidate candidate) {
		ComplexReadEntity complex = candidate.complex();
		return new ParcelDetailResult(
			parcel.id(),
			complex.id(),
			latitude(candidate.displayCoordinate(), parcel),
			longitude(candidate.displayCoordinate(), parcel),
			parcel.address(),
			complex.tradeName(),
			complex.name(),
			complex.dongCnt(),
			complex.unitCnt(),
			complex.platArea(),
			complex.archArea(),
			complex.totArea(),
			complex.bcRat(),
			complex.vlRat(),
			complex.useDate()
		);
	}

	private List<DetailCandidate> findDetailCandidates(Long parcelId, Long complexId) {
		return entityManager.createQuery("""
			SELECT c, displayCoordinate,
			       (SELECT MAX(t.dealDate) FROM TradeReadEntity t WHERE t.complexId = c.id AND t.deletedAt IS NULL),
			       (SELECT MIN(t.dealDate) FROM TradeReadEntity t WHERE t.complexId = c.id AND t.deletedAt IS NULL)
			FROM ComplexReadEntity c
			LEFT JOIN ComplexDisplayCoordinateReadEntity displayCoordinate ON displayCoordinate.complexId = c.id
			WHERE c.parcelId = :parcelId
			  AND (:complexId IS NULL OR c.id = :complexId)
			""", Object[].class)
			.setParameter("parcelId", parcelId)
			.setParameter("complexId", complexId)
			.getResultList()
			.stream()
			.map(row -> new DetailCandidate(
				(ComplexReadEntity) row[0],
				(ComplexDisplayCoordinateReadEntity) row[1],
				(LocalDate) row[2],
				(LocalDate) row[3]
			))
			.toList();
	}

	private DetailCandidate representativeCandidate(Long parcelId, List<DetailCandidate> candidates) {
		if (!isHighConfidenceRedevelopedParcel(parcelId)) {
			return candidates.stream()
				.min(Comparator.comparing(candidate -> candidate.complex().id()))
				.orElseThrow();
		}
		return candidates.stream()
			.min(this::compareRedevelopedRepresentative)
			.orElseThrow();
	}

	private boolean isHighConfidenceRedevelopedParcel(Long parcelId) {
		Long count = entityManager.createQuery("""
			SELECT count(c)
			FROM ComplexCoordinateCaseReadEntity c
			WHERE c.parcelId = :parcelId
			  AND c.relationType = 'REDEVELOPED'
			  AND c.relationConfidence = 'HIGH'
			""", Long.class)
			.setParameter("parcelId", parcelId)
			.getSingleResult();
		return count > 0;
	}

	private int compareRedevelopedRepresentative(DetailCandidate left, DetailCandidate right) {
		int useDate = compareNullableDesc(left.complex().useDate(), right.complex().useDate());
		if (useDate != 0) {
			return useDate;
		}
		int latestDealDate = compareNullableDesc(left.latestDealDate(), right.latestDealDate());
		if (latestDealDate != 0) {
			return latestDealDate;
		}
		int firstDealDate = compareNullableDesc(left.firstDealDate(), right.firstDealDate());
		if (firstDealDate != 0) {
			return firstDealDate;
		}
		return Long.compare(right.complex().id(), left.complex().id());
	}

	private <T extends Comparable<T>> int compareNullableDesc(T left, T right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		return right.compareTo(left);
	}

	private boolean hasComplexParent(Long parcelId, Long complexId) {
		Long count = entityManager.createQuery("""
			SELECT count(c)
			FROM ComplexReadEntity c
			WHERE c.parcelId = :parcelId
			  AND (:complexId IS NULL OR c.id = :complexId)
			""", Long.class)
			.setParameter("parcelId", parcelId)
			.setParameter("complexId", complexId)
			.getSingleResult();
		return count > 0;
	}

	private Double latitude(ComplexDisplayCoordinateReadEntity displayCoordinate, ParcelReadEntity parcel) {
		return displayCoordinate == null ? parcel.latitude() : displayCoordinate.latitude();
	}

	private Double longitude(ComplexDisplayCoordinateReadEntity displayCoordinate, ParcelReadEntity parcel) {
		return displayCoordinate == null ? parcel.longitude() : displayCoordinate.longitude();
	}

	private String normalizeName(String value) {
		String text = value == null ? "" : value.trim();
		return text.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}

	private record DetailCandidate(
		ComplexReadEntity complex,
		ComplexDisplayCoordinateReadEntity displayCoordinate,
		LocalDate latestDealDate,
		LocalDate firstDealDate
	) {
	}
}
