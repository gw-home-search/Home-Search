package com.home.infrastructure.web.read.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.home.application.read.TradeListResult;

public record TradeListResponse(
	Long parcelId,
	Long complexId,
	@JsonUnwrapped PageResponse<TradeResponse> page
) {

	public static TradeListResponse from(TradeListResult result) {
		return new TradeListResponse(
			result.parcelId(),
			result.complexId(),
			PageResponse.of(
				result.trades().stream().map(TradeResponse::from).toList(),
				result.page(),
				result.size(),
				result.totalElements()
			)
		);
	}
}
