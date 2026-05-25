package com.home.application.read;

import java.util.List;
import java.util.Optional;

import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

public class EmptyPropertyReadRepository implements PropertyReadRepository {

	@Override
	public List<SearchComplexResponse> searchComplexes(String query) {
		return List.of();
	}

	@Override
	public List<RegionSummaryResponse> findRootRegions() {
		return List.of();
	}

	@Override
	public Optional<RegionDetailResponse> findRegionDetail(Long regionId) {
		return Optional.empty();
	}

	@Override
	public Optional<ParcelDetailResponse> findParcelDetail(Long parcelId) {
		return Optional.empty();
	}

	@Override
	public Optional<TradeListResponse> findTradeList(Long parcelId) {
		return Optional.empty();
	}
}
