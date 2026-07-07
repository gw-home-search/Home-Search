package com.home.infrastructure.external.odcloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier;
import com.home.application.ingest.matching.ComplexIdentityResolver;

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
	@DisplayName("ODC coordinate identity gate는 enable property가 true이고 key가 있으면 verifier bean을 만든다")
	void odcloudCoordinateIdentityGateCreatesVerifierWhenEnabled() {
		contextRunner
			.withPropertyValues(
				"complex.coordinate.identity.odcloud.enabled=true",
				"odcloud.data.od-service-key=ODC-KEY"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(ComplexCoordinateIdentityVerifier.class);
				assertThat(context.getBean(ComplexCoordinateIdentityVerifier.class))
					.isInstanceOf(OdcloudComplexCoordinateIdentityVerifier.class);
			});
	}

	@Test
	@DisplayName("ODC coordinate identity verifier는 service key가 있으면 enable 플래그 없이 기본 등록된다")
	void odcloudCoordinateIdentityVerifierIsDefaultWhenServiceKeyConfigured() {
		contextRunner
			.withPropertyValues("odcloud.data.od-service-key=ODC-KEY")
			.run(context -> {
				assertThat(context).hasSingleBean(ComplexCoordinateIdentityVerifier.class);
				assertThat(context.getBean(ComplexCoordinateIdentityVerifier.class))
					.isInstanceOf(OdcloudComplexCoordinateIdentityVerifier.class);
			});
	}

	@Test
	@DisplayName("ODC coordinate identity verifier는 service key가 없으면 non-blocking trusting fallback으로 등록된다")
	void odcloudCoordinateIdentityVerifierFallsBackToTrustingWithoutServiceKey() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ComplexCoordinateIdentityVerifier.class);
			assertThat(context.getBean(ComplexCoordinateIdentityVerifier.class))
				.isNotInstanceOf(OdcloudComplexCoordinateIdentityVerifier.class);
		});
	}

	@Test
	@DisplayName("ODC coordinate identity verifier는 명시적으로 비활성화하면 등록되지 않는다")
	void odcloudCoordinateIdentityVerifierCanBeExplicitlyDisabled() {
		contextRunner
			.withPropertyValues(
				"complex.coordinate.identity.odcloud.enabled=false",
				"odcloud.data.od-service-key=ODC-KEY"
			)
			.run(context -> assertThat(context).doesNotHaveBean(ComplexCoordinateIdentityVerifier.class));
	}
}
