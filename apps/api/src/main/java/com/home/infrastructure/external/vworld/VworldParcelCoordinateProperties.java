package com.home.infrastructure.external.vworld;

record VworldParcelCoordinateProperties(
	String baseUrl,
	String wfsPath,
	String serviceKey,
	String domain,
	int numOfRows,
	int connectTimeoutMillis,
	int readTimeoutMillis
) {

	VworldParcelCoordinateProperties {
		baseUrl = hasText(baseUrl) ? baseUrl.trim() : "https://api.vworld.kr";
		wfsPath = hasText(wfsPath) ? wfsPath.trim() : "/ned/wfs/getBldgisSpceWFS";
		serviceKey = hasText(serviceKey) ? serviceKey.trim() : "";
		domain = hasText(domain) ? domain.trim() : "http://localhost:8080/only-local-test";
		numOfRows = numOfRows > 0 ? numOfRows : 100;
		connectTimeoutMillis = connectTimeoutMillis > 0 ? connectTimeoutMillis : 5_000;
		readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : 5_000;
	}

	boolean hasServiceKey() {
		return hasText(serviceKey);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
