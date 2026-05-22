package com.home.infrastructure.external.rtms;

import com.home.application.ingest.OpenApiTradeIngestBatch;

public interface RtmsApartmentTradeClient {

	OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request);
}
