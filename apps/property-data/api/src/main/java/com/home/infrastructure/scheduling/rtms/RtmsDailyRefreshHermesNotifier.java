package com.home.infrastructure.scheduling.rtms;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RtmsDailyRefreshHermesNotifier implements RtmsDailyRefreshNotifier {

	private static final String SOURCE = "home-search";
	private static final String TARGET = "slack";
	private static final String EVENT_TYPE = "rtms-daily-refresh";

	private final RestClient restClient;
	private final String url;
	private final String authToken;
	private final String channel;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;

	RtmsDailyRefreshHermesNotifier(
		String url,
		String authToken,
		String channel,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) {
		this(
			restClient(connectTimeoutMillis, readTimeoutMillis),
			url,
			authToken,
			channel,
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	RtmsDailyRefreshHermesNotifier(
		RestClient restClient,
		String url,
		String authToken,
		String channel,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.url = requireText(url, "url");
		this.authToken = authToken == null ? "" : authToken.trim();
		this.channel = requireText(channel, "channel");
		if (connectTimeoutMillis <= 0) {
			throw new IllegalArgumentException("connectTimeoutMillis must be positive");
		}
		if (readTimeoutMillis <= 0) {
			throw new IllegalArgumentException("readTimeoutMillis must be positive");
		}
		this.connectTimeoutMillis = connectTimeoutMillis;
		this.readTimeoutMillis = readTimeoutMillis;
	}

	@Override
	public void send(String message) {
		RestClient.RequestBodySpec request = restClient.post()
			.uri(url)
			.contentType(MediaType.APPLICATION_JSON);
		if (!authToken.isBlank()) {
			request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
		}
		request
			.body(Map.of(
				"target", TARGET,
				"channel", channel,
				"source", SOURCE,
				"eventType", EVENT_TYPE,
				"text", Objects.requireNonNull(message)
			))
			.retrieve()
			.toBodilessEntity();
	}

	String channel() {
		return channel;
	}

	int connectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	int readTimeoutMillis() {
		return readTimeoutMillis;
	}

	private static RestClient restClient(int connectTimeoutMillis, int readTimeoutMillis) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return RestClient.builder()
			.requestFactory(requestFactory)
			.build();
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
