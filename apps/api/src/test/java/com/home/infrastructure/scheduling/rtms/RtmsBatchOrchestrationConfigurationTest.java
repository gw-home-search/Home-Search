package com.home.infrastructure.scheduling.rtms;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.infrastructure.external.rtms.RtmsApartmentTradeRequest;
import com.home.infrastructure.external.rtms.RtmsCoordinateSourceAvailabilityProbe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RtmsBatchOrchestrationConfigurationTest {

	private final ApplicationContextRunner propertiesContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	@DisplayName("RTMS one-shot 설정은 기존 property key를 의미 단위 설정 객체로 바인딩한다")
	void rtmsOneShotPropertiesBindExistingKeys() {
		propertiesContextRunner
			.withPropertyValues(
				"home.ingest.rtms.enabled=true",
				"home.ingest.rtms.lawd-cd=11680",
				"home.ingest.rtms.deal-ymd=202606",
				"home.ingest.rtms.page-no=2",
				"home.ingest.rtms.preflight-only=true",
				"home.ingest.rtms.mode=monthly-refresh",
				"home.ingest.rtms.lookback-months=3",
				"home.ingest.rtms.allow-coordinate-pending-only=true"
			)
			.run(context -> {
				RtmsOneShotIngestConfigurationProperties bound =
					context.getBean(RtmsOneShotIngestConfigurationProperties.class);
				RtmsOneShotIngestProperties properties = bound.toProperties();

				org.assertj.core.api.Assertions.assertThat(properties).satisfies(value -> {
					org.assertj.core.api.Assertions.assertThat(value.request())
						.isEqualTo(new RtmsApartmentTradeRequest("11680", "202606", 2));
					org.assertj.core.api.Assertions.assertThat(value.preflightOnly()).isTrue();
					org.assertj.core.api.Assertions.assertThat(value.lookbackMonths()).isEqualTo(3);
					org.assertj.core.api.Assertions.assertThat(value.allowCoordinatePendingOnly()).isTrue();
					org.assertj.core.api.Assertions.assertThat(value.ingestMode())
						.isEqualTo(RtmsIngestMode.MONTHLY_REFRESH);
				});
			});
	}

	@Test
	@DisplayName("preflight bean은 allow-coordinate-pending-only를 ConfigurationProperties 단일 출처에서 읽는다")
	void preflightBeanReadsPendingOnlyFlagFromSingleConfigurationSource() {
		RtmsBatchOrchestrationConfiguration configuration = new RtmsBatchOrchestrationConfiguration();
		RtmsCoordinateSourceAvailabilityProbe probe = mock(RtmsCoordinateSourceAvailabilityProbe.class);

		configuration.rtmsCoordinateSourcePreflight(preflightProperties(true), probe).verify();
		verifyNoInteractions(probe);

		when(probe.configured()).thenReturn(true);
		configuration.rtmsCoordinateSourcePreflight(preflightProperties(false), probe).verify();
		verify(probe).verifyAvailable();
	}

	private static RtmsOneShotIngestProperties preflightProperties(boolean allowCoordinatePendingOnly) {
		return new RtmsOneShotIngestProperties(
			true,
			"11680",
			"202606",
			1,
			false,
			"one-shot",
			0,
			allowCoordinatePendingOnly
		);
	}

	@EnableConfigurationProperties(RtmsOneShotIngestConfigurationProperties.class)
	private static class PropertiesConfiguration {
	}
}
