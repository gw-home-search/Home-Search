package com.home.application.read;

import java.util.List;
import java.util.Objects;

import com.home.global.error.ResourceNotFoundException;

public class PropertyReadUseCase {

	private static final int SUGGESTION_LIMIT = 8;
	private static final int DEFAULT_REGION_COMPLEX_LIMIT = 50;
	private static final int MAX_REGION_COMPLEX_LIMIT = 100;

	private final PropertyReadRepository repository;

	public PropertyReadUseCase(PropertyReadRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public List<SearchComplexResult> searchComplexes(String query) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		return repository.searchComplexes(trimmed);
	}

	public List<ComplexSuggestionResult> suggestComplexes(String query) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		return repository.suggestComplexes(trimmed, SUGGESTION_LIMIT);
	}

	public List<RegionSummaryResult> getRootRegions() {
		return repository.findRootRegions();
	}

	public RegionDetailResult getRegionDetail(Long regionId) {
		return repository.findRegionDetail(regionId)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public List<ComplexSummaryResult> getRegionComplexes(Long regionId, Integer requestedLimit, Integer requestedOffset) {
		int limit = normalizeLimit(requestedLimit);
		int offset = normalizeOffset(requestedOffset);
		return repository.findRegionComplexes(regionId, limit, offset)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public ParcelDetailResult getParcelDetail(Long parcelId) {
		return getParcelDetail(parcelId, null);
	}

	public ParcelDetailResult getParcelDetail(Long parcelId, Long complexId) {
		return repository.findParcelDetail(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel detail not found: " + parcelId));
	}

	public List<ComplexSummaryResult> getParcelComplexes(Long parcelId) {
		return repository.findParcelComplexes(parcelId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel not found: " + parcelId));
	}

	public ParcelDetailResult getComplexDetail(Long complexId) {
		return repository.findComplexDetail(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex detail not found: " + complexId));
	}

	public TradeListResult getTradeList(Long parcelId) {
		return getTradeList(parcelId, null);
	}

	public TradeListResult getTradeList(Long parcelId, Long complexId) {
		return repository.findTradeList(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}

	public TradeListResult getComplexTradeList(Long complexId) {
		return repository.findComplexTradeList(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex trade parent not found: " + complexId));
	}

	private int normalizeLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_REGION_COMPLEX_LIMIT;
		}
		if (requestedLimit < 1) {
			throw new InvalidReadRequestException("limit must be greater than 0");
		}
		return Math.min(requestedLimit, MAX_REGION_COMPLEX_LIMIT);
	}

	private int normalizeOffset(Integer requestedOffset) {
		if (requestedOffset == null) {
			return 0;
		}
		if (requestedOffset < 0) {
			throw new InvalidReadRequestException("offset must be greater than or equal to 0");
		}
		return requestedOffset;
	}
}
