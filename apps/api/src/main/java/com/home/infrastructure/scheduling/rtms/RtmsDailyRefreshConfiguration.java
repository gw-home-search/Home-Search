package com.home.infrastructure.scheduling.rtms;

import java.time.Clock;

import com.home.application.region.RegionUnitCntSynchronizationService;

import org.springframework.beans.factory.ObjectProvider;
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
		@Value("${home.ingest.rtms.daily.hermes.enabled:false}") boolean enabled,
		@Value("${home.ingest.rtms.daily.hermes.url:${HERMES_SLACK_URL:}}") String url,
		@Value("${home.ingest.rtms.daily.hermes.auth-token:${HERMES_AUTH_TOKEN:}}") String authToken,
		@Value("${home.ingest.rtms.daily.hermes.channel:${HERMES_SLACK_CHANNEL:}}") String channel,
		@Value("${home.ingest.rtms.daily.hermes.connect-timeout-millis:3000}") int connectTimeoutMillis,
		@Value("${home.ingest.rtms.daily.hermes.read-timeout-millis:3000}") int readTimeoutMillis
	) {
		if (!enabled || url == null || url.isBlank() || channel == null || channel.isBlank()) {
			return RtmsDailyRefreshNotifier.noop();
		}
		return new RtmsDailyRefreshHermesNotifier(
			url.trim(),
			authToken == null ? "" : authToken.trim(),
			channel.trim(),
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Bean
	RtmsDailyRefreshScheduler rtmsDailyRefreshScheduler(
		RtmsMonthlyRefreshRunner monthlyRefreshRunner,
		RtmsDailyRefreshProperties properties,
		RtmsDailyRefreshSlackMessageFormatter formatter,
		RtmsDailyRefreshNotifier notifier,
		ObjectProvider<RegionUnitCntSynchronizationService> regionSynchronizationServiceProvider
	) {
		return new RtmsDailyRefreshScheduler(
			monthlyRefreshRunner,
			properties,
			formatter,
			notifier,
			Clock.system(properties.zoneId()),
			regionSynchronizationServiceProvider.getIfAvailable()
		);
	}
}
