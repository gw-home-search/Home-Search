package com.home.infrastructure.external.rtms;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "home.ingest.rtms.daily.enabled", havingValue = "true")
class RtmsDailyRefreshConfiguration {

	@Bean
	RtmsDailyRefreshProperties rtmsDailyRefreshProperties(
		@Value("${home.ingest.rtms.daily.lawd-cds:}") String lawdCds,
		@Value("${home.ingest.rtms.daily.lookback-months:1}") int lookbackMonths,
		@Value("${home.ingest.rtms.daily.zone:Asia/Seoul}") String zone
	) {
		return RtmsDailyRefreshProperties.from(lawdCds, lookbackMonths, zone);
	}

	@Bean
	RtmsDailyRefreshSlackMessageFormatter rtmsDailyRefreshSlackMessageFormatter() {
		return new RtmsDailyRefreshSlackMessageFormatter();
	}

	@Bean
	RtmsDailyRefreshNotifier rtmsDailyRefreshNotifier(
		@Value("${home.ingest.rtms.daily.slack.enabled:false}") boolean enabled,
		@Value("${home.ingest.rtms.daily.slack.webhook-url:${SLACK_WEBHOOK_URL:}}") String webhookUrl,
		@Value("${home.ingest.rtms.daily.slack.connect-timeout-millis:3000}") int connectTimeoutMillis,
		@Value("${home.ingest.rtms.daily.slack.read-timeout-millis:3000}") int readTimeoutMillis
	) {
		if (!enabled || webhookUrl == null || webhookUrl.isBlank()) {
			return RtmsDailyRefreshNotifier.noop();
		}
		return new RtmsDailyRefreshWebhookNotifier(webhookUrl.trim(), connectTimeoutMillis, readTimeoutMillis);
	}

	@Bean
	RtmsDailyRefreshScheduler rtmsDailyRefreshScheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsDailyRefreshProperties properties,
		RtmsDailyRefreshSlackMessageFormatter formatter,
		RtmsDailyRefreshNotifier notifier
	) {
		return new RtmsDailyRefreshScheduler(
			monthlyRefreshRunner,
			properties,
			formatter,
			notifier,
			Clock.system(properties.zoneId())
		);
	}
}
