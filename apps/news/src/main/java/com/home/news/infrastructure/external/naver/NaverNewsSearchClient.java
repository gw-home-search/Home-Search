package com.home.news.infrastructure.external.naver;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.NewsCollectionException;
import com.home.news.application.NewsMetadataClient;
import com.home.news.application.NewsSearchResult;

public class NaverNewsSearchClient implements NewsMetadataClient {

	private static final String NAVER_HEADER_PREFIX = "X-Naver-Client-";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final NaverNewsSearchResponseParser parser;
	private final NewsRuntimeProperties properties;

	public NaverNewsSearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		NaverNewsSearchResponseParser parser,
		NewsRuntimeProperties properties
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.parser = parser;
		this.properties = properties;
	}

	@Override
	public NewsSearchResult search(String queryText, int display, String sortOrder) {
		validateCredentials();
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(searchUri(queryText, display, sortOrder))
				.header(NAVER_HEADER_PREFIX + "Id", properties.getNaver().getClientId())
				.header(NAVER_HEADER_PREFIX + "Secret", properties.getNaver().getClientSecret())
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new NewsCollectionException("Naver News Search request failed with status " + response.statusCode());
			}
			return parser.parse(response.body());
		}
		catch (IOException ex) {
			throw new NewsCollectionException("Naver News Search request failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NewsCollectionException("Naver News Search request interrupted", ex);
		}
	}

	@SuppressWarnings("unused")
	private ObjectMapper objectMapper() {
		return objectMapper;
	}

	private URI searchUri(String queryText, int display, String sortOrder) {
		String query = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
		String sort = URLEncoder.encode(sortOrder, StandardCharsets.UTF_8);
		return URI.create(properties.getNaver().getBaseUrl()
			+ "?query=" + query
			+ "&display=" + display
			+ "&start=1"
			+ "&sort=" + sort);
	}

	private void validateCredentials() {
		if (properties.getNaver().getClientId() == null || properties.getNaver().getClientId().isBlank()
			|| properties.getNaver().getClientSecret() == null || properties.getNaver().getClientSecret().isBlank()) {
			throw new NewsCollectionException("Naver News Search credentials are required when news run-once is enabled");
		}
	}
}
