package com.home.infrastructure.external.rtms;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RtmsDailyRefreshWebhookNotifierTest {

	@Test
	@DisplayName("daily refresh Slack webhook notifier는 summary text를 JSON payload로 전송한다")
	void webhookNotifierSendsSummaryTextAsJsonPayload() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RtmsDailyRefreshWebhookNotifier notifier = new RtmsDailyRefreshWebhookNotifier(
			builder.build(),
			"https://slack.example.invalid/webhook",
			1000,
			1000
		);

		server.expect(once(), requestTo("https://slack.example.invalid/webhook"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"text\":\"RTMS daily refresh\"}"))
			.andRespond(withSuccess());

		notifier.send("RTMS daily refresh");

		server.verify();
	}
}
