package com.home.infrastructure.ops.notification;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class HermesOpsNotifier implements OpsNotifier {

	private final RestClient restClient;
	private final String authToken;
	private final String channel;

	public HermesOpsNotifier(RestClient restClient, String authToken, String channel) {
		this.restClient = restClient;
		this.authToken = authToken == null ? "" : authToken;
		this.channel = channel;
	}

	@Override
	public void send(OpsNotification notification) {
		restClient.post()
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", authToken.isBlank() ? "" : "Bearer " + authToken)
			.body(Map.of(
				"channel", channel,
				"text", "*" + notification.title() + "*\n" + notification.message()
			))
			.retrieve()
			.toBodilessEntity();
	}
}
