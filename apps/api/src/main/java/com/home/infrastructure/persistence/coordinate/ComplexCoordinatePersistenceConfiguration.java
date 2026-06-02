package com.home.infrastructure.persistence.coordinate;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.coordinate.BuildingFootprintSource;
import com.home.application.coordinate.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.ComplexCoordinateExceptionService;
import com.home.application.coordinate.ComplexCoordinateIdentityVerifier;
import com.home.application.coordinate.ComplexCoordinateReadinessRepository;
import com.home.application.coordinate.ComplexCoordinateReadinessService;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.persistence.complex.JdbcComplexRelationRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ComplexCoordinatePersistenceConfiguration {

	@Bean
	@Lazy
	ComplexCoordinateExceptionRepository complexCoordinateExceptionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexCoordinateExceptionRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ComplexCoordinateReadinessRepository complexCoordinateReadinessRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexCoordinateExceptionRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ComplexCoordinateExceptionService complexCoordinateExceptionService(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<ComplexCoordinateIdentityVerifier> identityVerifierProvider,
		ObjectProvider<BuildingFootprintSource> buildingFootprintSourceProvider
	) {
		JdbcClient jdbcClient = requiredJdbcClient(jdbcClientProvider);
		return new ComplexCoordinateExceptionService(
			new JdbcComplexCoordinateExceptionRepository(jdbcClient),
			new JdbcComplexRelationRepository(jdbcClient),
			new ComplexRelationClassifier(),
			identityVerifierProvider.getIfAvailable(ComplexCoordinateIdentityVerifier::trusting),
			buildingFootprintSourceProvider.getIfAvailable(BuildingFootprintSource::unavailable)
		);
	}

	@Bean
	@Lazy
	ComplexDisplayCoordinateProjectionRepository complexDisplayCoordinateProjectionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexDisplayCoordinateProjectionRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	ComplexDisplayCoordinateProjectionService complexDisplayCoordinateProjectionService(
		ComplexDisplayCoordinateProjectionRepository repository
	) {
		return new ComplexDisplayCoordinateProjectionService(repository);
	}

	@Bean
	@Lazy
	ComplexCoordinateReadinessService complexCoordinateReadinessService(
		ComplexCoordinateExceptionService complexCoordinateExceptionService,
		ComplexCoordinateReadinessRepository complexCoordinateReadinessRepository,
		ComplexDisplayCoordinateProjectionService complexDisplayCoordinateProjectionService
	) {
		return new ComplexCoordinateReadinessService(
			complexCoordinateExceptionService,
			complexCoordinateReadinessRepository,
			complexDisplayCoordinateProjectionService
		);
	}

	@Bean
	@ConditionalOnProperty(name = "home.coordinate.readiness.enabled", havingValue = "true")
	ApplicationRunner complexCoordinateReadinessRunner(
		ComplexCoordinateReadinessService complexCoordinateReadinessService,
		@Value("${home.coordinate.readiness.stage-limit:500}") int stageLimit,
		@Value("${home.coordinate.readiness.resolve-limit:500}") int resolveLimit,
		@Value("${home.coordinate.readiness.project-limit:1000}") int projectLimit
	) {
		return new ComplexCoordinateReadinessRunner(
			complexCoordinateReadinessService,
			stageLimit,
			resolveLimit,
			projectLimit
		);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for complex coordinate persistence");
		});
	}
}
