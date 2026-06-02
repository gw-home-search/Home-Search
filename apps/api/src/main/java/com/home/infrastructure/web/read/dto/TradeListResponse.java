package com.home.infrastructure.web.read.dto;

import java.util.List;

public record TradeListResponse(
	Long parcelId,
	Long complexId,
	List<TradeResponse> trades
) {

	public TradeListResponse(Long parcelId, List<TradeResponse> trades) {
		this(parcelId, null, trades);
	}
}
