package com.home.application.read;

import java.util.List;

public record TradeListResult(
	Long parcelId,
	Long complexId,
	List<TradeResult> trades
) {
}
