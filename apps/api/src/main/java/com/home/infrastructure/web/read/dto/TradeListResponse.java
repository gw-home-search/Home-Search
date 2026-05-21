package com.home.infrastructure.web.read.dto;

import java.util.List;

public record TradeListResponse(
	Long parcelId,
	List<TradeResponse> trades
) {
}
