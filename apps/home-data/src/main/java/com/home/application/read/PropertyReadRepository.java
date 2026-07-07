package com.home.application.read;

import java.util.List;
import java.util.Optional;

public interface PropertyReadRepository {

	List<SearchComplexResult> searchComplexes(String query);

	List<ComplexSuggestionResult> suggestComplexes(String query, int limit);

	List<RegionSummaryResult> findRootRegions();

	Optional<RegionDetailResult> findRegionDetail(Long regionId);

	Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset);

	default Optional<ParcelDetailResult> findParcelDetail(Long parcelId) {
		return findParcelDetail(parcelId, null);
	}

	Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId);

	Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId);

	Optional<ParcelDetailResult> findComplexDetail(Long complexId);

	default Optional<TradeListResult> findTradeList(Long parcelId) {
		return findTradeList(parcelId, null);
	}

	default Optional<TradeListResult> findTradeList(Long parcelId, Long complexId) {
		return findTradeList(parcelId, complexId, 0, Integer.MAX_VALUE);
	}

	Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size);

	default Optional<TradeListResult> findComplexTradeList(Long complexId) {
		return findComplexTradeList(complexId, 0, Integer.MAX_VALUE);
	}

	Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size);

	Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId);

	Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId);
}
