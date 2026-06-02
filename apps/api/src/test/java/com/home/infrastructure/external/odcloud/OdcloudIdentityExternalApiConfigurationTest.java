package com.home.infrastructure.external.odcloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.coordinate.ComplexCoordinateIdentityVerifier;
import com.home.application.ingest.ComplexIdentityResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OdcloudIdentityExternalApiConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(OdcloudIdentityExternalApiConfiguration.class);

	@Test
	@DisplayName("ODC identity fallback은 기본 설정에서 ingest 경로에 연결되지 않는다")
	void odcloudIdentityFallbackIsDisabledByDefault() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(ComplexIdentityResolver.class));
	}

	@Test
	@DisplayName("ODC identity fallback은 opt-in property가 true일 때만 resolver bean을 만든다")
	void odcloudIdentityFallbackCreatesResolverWhenEnabled() {
		contextRunner
			.withPropertyValues(
				"complex.identity.odcloud.enabled=true",
				"odcloud.data.od-service-key=ODC-KEY"
			)
			.run(context -> assertThat(context).hasSingleBean(ComplexIdentityResolver.class));
	}

	@Test
	@DisplayName("ODC coordinate identity gate는 opt-in property가 true일 때만 verifier bean을 만든다")
	void odcloudCoordinateIdentityGateCreatesVerifierWhenEnabled() {
		contextRunner
			.withPropertyValues(
				"complex.coordinate.identity.odcloud.enabled=true",
				"odcloud.data.od-service-key=ODC-KEY"
			)
			.run(context -> assertThat(context).hasSingleBean(ComplexCoordinateIdentityVerifier.class));
	}
}
