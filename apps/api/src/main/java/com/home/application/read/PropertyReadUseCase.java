package com.home.application.read;

import java.util.List;
import java.util.Objects;

import com.home.global.error.ResourceNotFoundException;

public class PropertyReadUseCase {

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

	public List<RegionSummaryResult> getRootRegions() {
		return repository.findRootRegions();
	}

	public RegionDetailResult getRegionDetail(Long regionId) {
		return repository.findRegionDetail(regionId)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public ParcelDetailResult getParcelDetail(Long parcelId) {
		return getParcelDetail(parcelId, null);
	}

	public ParcelDetailResult getParcelDetail(Long parcelId, Long complexId) {
		return repository.findParcelDetail(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel detail not found: " + parcelId));
	}

	public TradeListResult getTradeList(Long parcelId) {
		return getTradeList(parcelId, null);
	}

	public TradeListResult getTradeList(Long parcelId, Long complexId) {
		return repository.findTradeList(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}
}
