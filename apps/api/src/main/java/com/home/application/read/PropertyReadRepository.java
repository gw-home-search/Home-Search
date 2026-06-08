package com.home.application.read;

import java.util.List;
import java.util.Optional;

public interface PropertyReadRepository {

	List<SearchComplexResult> searchComplexes(String query);

	List<RegionSummaryResult> findRootRegions();

	Optional<RegionDetailResult> findRegionDetail(Long regionId);

	default Optional<ParcelDetailResult> findParcelDetail(Long parcelId) {
		return findParcelDetail(parcelId, null);
	}

	Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId);

	default Optional<TradeListResult> findTradeList(Long parcelId) {
		return findTradeList(parcelId, null);
	}

	Optional<TradeListResult> findTradeList(Long parcelId, Long complexId);
}
