package com.home.infrastructure.external.rtms;

import java.util.Objects;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;

public record RtmsApartmentTradePage(
	OpenApiTradeIngestBatch batch,
	int pageNo,
	int numOfRows,
	int totalCount
) {

	public RtmsApartmentTradePage {
		batch = Objects.requireNonNull(batch);
		if (pageNo < 1) {
			throw new IllegalArgumentException("pageNo must be greater than zero");
		}
		if (numOfRows < 1) {
			throw new IllegalArgumentException("numOfRows must be greater than zero");
		}
		if (totalCount < 0) {
			throw new IllegalArgumentException("totalCount must not be negative");
		}
	}

	public static RtmsApartmentTradePage single(OpenApiTradeIngestBatch batch) {
		int itemCount = batch.items().size();
		return new RtmsApartmentTradePage(batch, batch.pageNo(), Math.max(itemCount, 1), itemCount);
	}

	public boolean hasNextPage() {
		return (long) pageNo * numOfRows < totalCount;
	}

	public RtmsApartmentTradeRequest nextRequest() {
		return new RtmsApartmentTradeRequest(batch.lawdCd(), batch.dealYmd(), pageNo + 1);
	}
}
