package com.home.infrastructure.scheduling.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class RtmsDailyRefreshConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(RtmsDailyRefreshConfiguration.class);

	@Test
	@DisplayName("application.yml daily refresh cron 기본값은 매일 새벽 3시다")
	void applicationYamlDailyRefreshCronDefaultsToThreeAm() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));

		assertThat(yaml.getObject())
			.containsEntry("home.ingest.rtms.daily.cron", "0 0 3 * * *");
	}

	@Test
	@DisplayName("daily refresh scheduler는 기본 설정에서 등록되지 않는다")
	void dailyRefreshSchedulerIsDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(RtmsDailyRefreshScheduler.class);
		});
	}

	@Test
	@DisplayName("daily refresh scheduler는 enabled 설정이면 configured 법정동 목록으로 등록된다")
	void dailyRefreshSchedulerIsRegisteredWhenEnabled() {
		contextRunner
			.withBean(RtmsMonthlyRefreshRunner.class, () -> mock(RtmsMonthlyRefreshRunner.class))
			.withPropertyValues(
				"home.ingest.rtms.daily.enabled=true",
				"home.ingest.rtms.daily.lawd-cds=11680,11710",
				"home.ingest.rtms.daily.lookback-months=1"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RtmsDailyRefreshScheduler.class);
				assertThat(context.getBean(RtmsDailyRefreshProperties.class).lawdCds())
					.containsExactly("11680", "11710");
			});
	}

	@Test
	@DisplayName("daily refresh Hermes notifier는 configured routing과 timeout으로 등록된다")
	void dailyRefreshHermesNotifierUsesConfiguredRoutingAndTimeouts() {
		contextRunner
			.withBean(RtmsMonthlyRefreshRunner.class, () -> mock(RtmsMonthlyRefreshRunner.class))
			.withPropertyValues(
				"home.ingest.rtms.daily.enabled=true",
				"home.ingest.rtms.daily.lawd-cds=11680",
				"home.ingest.rtms.daily.hermes.enabled=true",
				"home.ingest.rtms.daily.hermes.url=https://hermes.example.invalid/api/notifications/slack",
				"home.ingest.rtms.daily.hermes.auth-token=test-token",
				"home.ingest.rtms.daily.hermes.channel=#home-search",
				"home.ingest.rtms.daily.hermes.connect-timeout-millis=1234",
				"home.ingest.rtms.daily.hermes.read-timeout-millis=2345"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(RtmsDailyRefreshNotifier.class))
					.isInstanceOfSatisfying(RtmsDailyRefreshHermesNotifier.class, notifier -> {
						assertThat(notifier.channel()).isEqualTo("#home-search");
						assertThat(notifier.connectTimeoutMillis()).isEqualTo(1234);
						assertThat(notifier.readTimeoutMillis()).isEqualTo(2345);
					});
			});
	}
}
