package com.home.infrastructure.web.read.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record TradeListResponse(
	Long parcelId,
	Long complexId,
	@JsonUnwrapped PageResponse<TradeResponse> page
) {
}
