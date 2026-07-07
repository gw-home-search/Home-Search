package com.home.infrastructure.external.rtms;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;

public interface RtmsApartmentTradeClient {

	OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request);

	default RtmsApartmentTradePage fetchPage(RtmsApartmentTradeRequest request) {
		return RtmsApartmentTradePage.single(fetch(request));
	}
}
