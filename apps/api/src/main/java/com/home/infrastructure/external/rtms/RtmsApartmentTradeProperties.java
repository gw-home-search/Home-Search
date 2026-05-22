package com.home.infrastructure.external.rtms;

public record RtmsApartmentTradeProperties(
	String baseUrl,
	String path,
	String serviceKey,
	int numOfRows,
	int connectTimeoutMillis,
	int readTimeoutMillis
) {

	public RtmsApartmentTradeProperties {
		baseUrl = hasText(baseUrl) ? baseUrl.trim() : "https://apis.data.go.kr";
		path = hasText(path) ? path.trim() : "/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev";
		numOfRows = numOfRows > 0 ? numOfRows : 1000;
		connectTimeoutMillis = connectTimeoutMillis > 0 ? connectTimeoutMillis : 5_000;
		readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : 5_000;
	}

	String requiredServiceKey() {
		if (!hasText(serviceKey)) {
			throw new IllegalStateException("APT_SERVICE_KEY is required for live RTMS calls");
		}
		return serviceKey.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
