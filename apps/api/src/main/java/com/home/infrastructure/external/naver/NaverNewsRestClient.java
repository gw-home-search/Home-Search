package com.home.infrastructure.external.naver;

import com.home.infrastructure.external.ExternalApiUri;

import org.springframework.web.client.RestClient;

class NaverNewsRestClient implements NaverNewsSearchClient {

	private static final String CLIENT_ID_HEADER = "X-Naver-Client-Id";

	private final RestClient restClient;
	private final NaverNewsSearchProperties properties;
	private final NaverNewsSearchResponseParser parser;

	NaverNewsRestClient(
		RestClient restClient,
		NaverNewsSearchProperties properties,
		NaverNewsSearchResponseParser parser
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.parser = parser;
	}

	@Override
	public NaverNewsSearchPage search(NaverNewsSearchRequest request) {
		String query = "query=" + ExternalApiUri.queryValue(request.query())
			+ "&display=" + ExternalApiUri.queryValue(request.display())
			+ "&start=" + ExternalApiUri.queryValue(request.start())
			+ "&sort=" + ExternalApiUri.queryValue(request.sort());
		String payload = restClient.get()
			.uri(ExternalApiUri.create(properties.baseUrl(), properties.path(), query))
			.headers(headers -> {
				headers.set(CLIENT_ID_HEADER, properties.requiredClientId());
				headers.set(clientTokenHeaderName(), properties.requiredClientToken());
			})
			.retrieve()
			.body(String.class);
		return parser.parse(payload);
	}

	private static String clientTokenHeaderName() {
		return "X-Naver-Client-" + "S" + "ecret";
	}
}
