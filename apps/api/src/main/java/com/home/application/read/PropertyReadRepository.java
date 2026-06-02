package com.home.application.read;

import java.util.List;
import java.util.Optional;

import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

public interface PropertyReadRepository {

	List<SearchComplexResponse> searchComplexes(String query);

	List<RegionSummaryResponse> findRootRegions();

	Optional<RegionDetailResponse> findRegionDetail(Long regionId);

	default Optional<ParcelDetailResponse> findParcelDetail(Long parcelId) {
		return findParcelDetail(parcelId, null);
	}

	Optional<ParcelDetailResponse> findParcelDetail(Long parcelId, Long complexId);

	default Optional<TradeListResponse> findTradeList(Long parcelId) {
		return findTradeList(parcelId, null);
	}

	Optional<TradeListResponse> findTradeList(Long parcelId, Long complexId);
}
