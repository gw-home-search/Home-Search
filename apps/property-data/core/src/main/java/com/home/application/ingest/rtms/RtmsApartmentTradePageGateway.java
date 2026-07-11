package com.home.application.ingest.rtms;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;

public interface RtmsApartmentTradePageGateway {

	OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request);

	default RtmsApartmentTradePage fetchPage(RtmsApartmentTradeRequest request) {
		return RtmsApartmentTradePage.single(fetch(request));
	}
}
