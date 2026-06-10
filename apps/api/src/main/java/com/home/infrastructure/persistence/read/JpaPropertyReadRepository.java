package com.home.infrastructure.persistence.read;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ComplexSuggestionResult;
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
		PropertySearchTerms terms = PropertySearchTerms.from(query);
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
			    CASE
			        WHEN lower(c.name) = :lowerQuery
			            OR lower(COALESCE(c.tradeName, '')) = :lowerQuery THEN 0
			        WHEN lower(c.name) LIKE :prefixPattern
			            OR lower(COALESCE(c.tradeName, '')) LIKE :prefixPattern THEN 1
			        WHEN EXISTS (
			            SELECT 1
			            FROM ComplexNameAliasReadEntity nameAlias
			            WHERE nameAlias.complexId = c.id
			              AND (
			                  lower(nameAlias.aliasName) = :lowerQuery
			                  OR lower(nameAlias.aliasName) LIKE :prefixPattern
			                  OR (
			                      :normalizedQuery IS NOT NULL
			                      AND (
			                          nameAlias.normalizedName = :normalizedQuery
			                          OR nameAlias.normalizedName LIKE :normalizedPrefixPattern
			                      )
			                  )
			              )
			        ) THEN 2
			        WHEN lower(c.name) LIKE :pattern
			            OR lower(COALESCE(c.tradeName, '')) LIKE :pattern THEN 3
			        WHEN EXISTS (
			            SELECT 1
			            FROM ComplexNameAliasReadEntity nameAlias
			            WHERE nameAlias.complexId = c.id
			              AND (
			                  lower(nameAlias.aliasName) LIKE :pattern
			                  OR (:normalizedPattern IS NOT NULL AND nameAlias.normalizedName LIKE :normalizedPattern)
			              )
			        ) THEN 4
			        WHEN lower(COALESCE(p.address, '')) LIKE :prefixPattern THEN 5
			        WHEN lower(COALESCE(p.address, '')) LIKE :pattern THEN 6
			        ELSE 7
			    END,
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    c.id
			""", Object[].class)
			.setParameter("lowerQuery", terms.lowerQuery())
			.setParameter("pattern", terms.pattern())
			.setParameter("prefixPattern", terms.prefixPattern())
			.setParameter("normalizedQuery", terms.normalizedQuery())
			.setParameter("normalizedPattern", terms.normalizedPattern())
			.setParameter("normalizedPrefixPattern", terms.normalizedPrefixPattern())
			.setMaxResults(20)
			.getResultList()
			.stream()
			.map(this::mapSearchComplex)
			.toList();
	}

	@Override
	public List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
		PropertySearchTerms terms = PropertySearchTerms.from(query);
		return entityManager.createQuery("""
			SELECT new com.home.application.read.ComplexSuggestionResult(
			    c.id,
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    p.id,
			    p.address
			)
			FROM ComplexReadEntity c
			JOIN ParcelReadEntity p ON p.id = c.parcelId
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
			    CASE
			        WHEN lower(c.name) = :lowerQuery
			            OR lower(COALESCE(c.tradeName, '')) = :lowerQuery THEN 0
			        WHEN lower(c.name) LIKE :prefixPattern
			            OR lower(COALESCE(c.tradeName, '')) LIKE :prefixPattern THEN 1
			        WHEN EXISTS (
			            SELECT 1
			            FROM ComplexNameAliasReadEntity nameAlias
			            WHERE nameAlias.complexId = c.id
			              AND (
			                  lower(nameAlias.aliasName) = :lowerQuery
			                  OR lower(nameAlias.aliasName) LIKE :prefixPattern
			                  OR (
			                      :normalizedQuery IS NOT NULL
			                      AND (
			                          nameAlias.normalizedName = :normalizedQuery
			                          OR nameAlias.normalizedName LIKE :normalizedPrefixPattern
			                      )
			                  )
			              )
			        ) THEN 2
			        WHEN lower(c.name) LIKE :pattern
			            OR lower(COALESCE(c.tradeName, '')) LIKE :pattern THEN 3
			        WHEN EXISTS (
			            SELECT 1
			            FROM ComplexNameAliasReadEntity nameAlias
			            WHERE nameAlias.complexId = c.id
			              AND (
			                  lower(nameAlias.aliasName) LIKE :pattern
			                  OR (:normalizedPattern IS NOT NULL AND nameAlias.normalizedName LIKE :normalizedPattern)
			              )
			        ) THEN 4
			        WHEN lower(COALESCE(p.address, '')) LIKE :prefixPattern THEN 5
			        WHEN lower(COALESCE(p.address, '')) LIKE :pattern THEN 6
			        ELSE 7
			    END,
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    c.id
			""", ComplexSuggestionResult.class)
			.setParameter("lowerQuery", terms.lowerQuery())
			.setParameter("pattern", terms.pattern())
			.setParameter("prefixPattern", terms.prefixPattern())
			.setParameter("normalizedQuery", terms.normalizedQuery())
			.setParameter("normalizedPattern", terms.normalizedPattern())
			.setParameter("normalizedPrefixPattern", terms.normalizedPrefixPattern())
			.setMaxResults(limit)
			.getResultList();
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
	public Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset) {
		if (entityManager.find(RegionReadEntity.class, regionId) == null) {
			return Optional.empty();
		}
		List<Long> regionIds = findRegionTreeIds(regionId);
		List<ComplexSummaryResult> complexes = entityManager.createQuery("""
			SELECT c, p, displayCoordinate
			FROM ComplexReadEntity c
			JOIN ParcelReadEntity p ON p.id = c.parcelId
			LEFT JOIN ComplexDisplayCoordinateReadEntity displayCoordinate ON displayCoordinate.complexId = c.id
			WHERE p.regionId IN :regionIds
			ORDER BY
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    c.id
			""", Object[].class)
			.setParameter("regionIds", regionIds)
			.setFirstResult(offset)
			.setMaxResults(limit)
			.getResultList()
			.stream()
			.map(this::mapComplexSummary)
			.toList();
		return Optional.of(complexes);
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
	public Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
		if (entityManager.find(ParcelReadEntity.class, parcelId) == null) {
			return Optional.empty();
		}
		List<ComplexSummaryResult> complexes = entityManager.createQuery("""
			SELECT c, p, displayCoordinate
			FROM ComplexReadEntity c
			JOIN ParcelReadEntity p ON p.id = c.parcelId
			LEFT JOIN ComplexDisplayCoordinateReadEntity displayCoordinate ON displayCoordinate.complexId = c.id
			WHERE p.id = :parcelId
			ORDER BY
			    CASE WHEN c.tradeName IS NOT NULL AND trim(c.tradeName) <> '' THEN c.tradeName ELSE c.name END,
			    c.id
			""", Object[].class)
			.setParameter("parcelId", parcelId)
			.getResultList()
			.stream()
			.map(this::mapComplexSummary)
			.toList();
		return Optional.of(complexes);
	}

	@Override
	public Optional<ParcelDetailResult> findComplexDetail(Long complexId) {
		List<Object[]> rows = entityManager.createQuery("""
			SELECT c, p, displayCoordinate
			FROM ComplexReadEntity c
			JOIN ParcelReadEntity p ON p.id = c.parcelId
			LEFT JOIN ComplexDisplayCoordinateReadEntity displayCoordinate ON displayCoordinate.complexId = c.id
			WHERE c.id = :complexId
			""", Object[].class)
			.setParameter("complexId", complexId)
			.getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Object[] row = rows.get(0);
		return Optional.of(mapParcelDetail(
			(ParcelReadEntity) row[1],
			new DetailCandidate((ComplexReadEntity) row[0], (ComplexDisplayCoordinateReadEntity) row[2], null, null)
		));
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

	@Override
	public Optional<TradeListResult> findComplexTradeList(Long complexId) {
		ComplexReadEntity complex = entityManager.find(ComplexReadEntity.class, complexId);
		if (complex == null) {
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
			WHERE t.complexId = :complexId
			  AND t.deletedAt IS NULL
			ORDER BY t.dealDate DESC, t.id DESC
			""", TradeResult.class)
			.setParameter("complexId", complexId)
			.getResultList();
		return Optional.of(new TradeListResult(complex.parcelId(), complexId, trades));
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

	private ComplexSummaryResult mapComplexSummary(Object[] row) {
		ComplexReadEntity complex = (ComplexReadEntity) row[0];
		ParcelReadEntity parcel = (ParcelReadEntity) row[1];
		ComplexDisplayCoordinateReadEntity displayCoordinate = (ComplexDisplayCoordinateReadEntity) row[2];
		return new ComplexSummaryResult(
			complex.id(),
			complex.displayName(),
			parcel.id(),
			latitude(displayCoordinate, parcel),
			longitude(displayCoordinate, parcel),
			parcel.address(),
			complex.dongCnt(),
			complex.unitCnt(),
			complex.useDate()
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

	private List<Long> findRegionTreeIds(Long rootRegionId) {
		List<Long> regionIds = new ArrayList<>();
		List<Long> frontier = List.of(rootRegionId);
		while (!frontier.isEmpty()) {
			regionIds.addAll(frontier);
			frontier = entityManager.createQuery("""
				SELECT r.id
				FROM RegionReadEntity r
				WHERE r.parentId IN :parentIds
				ORDER BY r.id
				""", Long.class)
				.setParameter("parentIds", frontier)
				.getResultList();
		}
		return regionIds;
	}

	private Double latitude(ComplexDisplayCoordinateReadEntity displayCoordinate, ParcelReadEntity parcel) {
		return displayCoordinate == null ? parcel.latitude() : displayCoordinate.latitude();
	}

	private Double longitude(ComplexDisplayCoordinateReadEntity displayCoordinate, ParcelReadEntity parcel) {
		return displayCoordinate == null ? parcel.longitude() : displayCoordinate.longitude();
	}

	private record DetailCandidate(
		ComplexReadEntity complex,
		ComplexDisplayCoordinateReadEntity displayCoordinate,
		LocalDate latestDealDate,
		LocalDate firstDealDate
	) {
	}
}
