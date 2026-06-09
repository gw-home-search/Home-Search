package com.home.infrastructure.external.rtms;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RtmsDailyRefreshWebhookNotifier implements RtmsDailyRefreshNotifier {

	private final RestClient restClient;
	private final String webhookUrl;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;

	RtmsDailyRefreshWebhookNotifier(String webhookUrl, int connectTimeoutMillis, int readTimeoutMillis) {
		this(
			restClient(connectTimeoutMillis, readTimeoutMillis),
			webhookUrl,
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	RtmsDailyRefreshWebhookNotifier(
		RestClient restClient,
		String webhookUrl,
		int connectTimeoutMillis,
		int readTimeoutMillis
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.webhookUrl = Objects.requireNonNull(webhookUrl);
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
		restClient.post()
			.uri(webhookUrl)
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("text", message))
			.retrieve()
			.toBodilessEntity();
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
}
