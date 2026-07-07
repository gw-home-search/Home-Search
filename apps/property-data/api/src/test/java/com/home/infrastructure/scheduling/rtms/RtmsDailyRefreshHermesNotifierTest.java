package com.home.infrastructure.scheduling.rtms;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RtmsDailyRefreshHermesNotifierTest {

	@Test
	@DisplayName("daily refresh Hermes notifier는 Slack routing 정보와 summary text를 전송한다")
	void hermesNotifierSendsSlackRoutingAndSummaryText() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RtmsDailyRefreshHermesNotifier notifier = new RtmsDailyRefreshHermesNotifier(
			builder.build(),
			"https://hermes.example.invalid/api/notifications/slack",
			"test-token",
			"#home-search",
			1000,
			1000
		);

		server.expect(once(), requestTo("https://hermes.example.invalid/api/notifications/slack"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
				{
				  "target": "slack",
				  "channel": "#home-search",
				  "source": "home-search",
				  "eventType": "rtms-daily-refresh",
				  "text": "RTMS daily refresh"
				}
				"""))
			.andRespond(withSuccess());

		notifier.send("RTMS daily refresh");

		server.verify();
	}
}
