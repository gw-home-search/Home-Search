package com.home.application.read;

import java.util.List;
import java.util.Objects;

import com.home.global.error.ResourceNotFoundException;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

public class PropertyReadUseCase {

	private final PropertyReadRepository repository;

	public PropertyReadUseCase(PropertyReadRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public List<SearchComplexResponse> searchComplexes(String query) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		return repository.searchComplexes(trimmed);
	}

	public List<RegionSummaryResponse> getRootRegions() {
		return repository.findRootRegions();
	}

	public RegionDetailResponse getRegionDetail(Long regionId) {
		return repository.findRegionDetail(regionId)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public ParcelDetailResponse getParcelDetail(Long parcelId) {
		return getParcelDetail(parcelId, null);
	}

	public ParcelDetailResponse getParcelDetail(Long parcelId, Long complexId) {
		return repository.findParcelDetail(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel detail not found: " + parcelId));
	}

	public TradeListResponse getTradeList(Long parcelId) {
		return getTradeList(parcelId, null);
	}

	public TradeListResponse getTradeList(Long parcelId, Long complexId) {
		return repository.findTradeList(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}
}
