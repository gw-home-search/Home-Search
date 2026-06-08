package com.home.application.read;

import java.util.List;
import java.util.Optional;

public class EmptyPropertyReadRepository implements PropertyReadRepository {

	@Override
	public List<SearchComplexResult> searchComplexes(String query) {
		return List.of();
	}

	@Override
	public List<RegionSummaryResult> findRootRegions() {
		return List.of();
	}

	@Override
	public Optional<RegionDetailResult> findRegionDetail(Long regionId) {
		return Optional.empty();
	}

	@Override
	public Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
		return Optional.empty();
	}

	@Override
	public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId) {
		return Optional.empty();
	}
}
