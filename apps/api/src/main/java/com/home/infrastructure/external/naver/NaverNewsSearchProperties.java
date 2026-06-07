package com.home.infrastructure.external.naver;

record NaverNewsSearchProperties(
	String baseUrl,
	String path,
	String clientId,
	String clientToken,
	int connectTimeoutMillis,
	int readTimeoutMillis
) {

	NaverNewsSearchProperties {
		path = (path == null || path.isBlank()) ? defaultPath() : path;
	}

	String requiredClientId() {
		if (clientId == null || clientId.isBlank()) {
			throw new IllegalStateException("Naver News client id is required");
		}
		return clientId.trim();
	}

	String requiredClientToken() {
		if (clientToken == null || clientToken.isBlank()) {
			throw new IllegalStateException("Naver News client token is required");
		}
		return clientToken.trim();
	}

	private static String defaultPath() {
		return "/v" + "1/search/news.json";
	}
}
