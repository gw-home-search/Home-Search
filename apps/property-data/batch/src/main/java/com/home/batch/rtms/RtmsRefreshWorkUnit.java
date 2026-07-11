package com.home.batch.rtms;

import com.home.application.ingest.rtms.RtmsApartmentTradeRequest;

public record RtmsRefreshWorkUnit(
	String lawdCd,
	String dealYmd
) {

	RtmsApartmentTradeRequest request() {
		return new RtmsApartmentTradeRequest(lawdCd, dealYmd, 1);
	}

	String serialized() {
		return lawdCd + ":" + dealYmd;
	}

	static RtmsRefreshWorkUnit parse(String value) {
		String[] parts = value.split(":", -1);
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid RTMS work unit: " + value);
		}
		return new RtmsRefreshWorkUnit(parts[0], parts[1]);
	}
}
