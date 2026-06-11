package com.home.infrastructure.persistence.coordinate;

import com.home.domain.complex.relation.ComplexRelationClassifier;
import com.home.application.coordinate.footprint.BuildingFootprintSource;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionService;
import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessRepository;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionService;
import com.home.application.coordinate.override.CoordinateOverrideAdminRepository;
import com.home.application.coordinate.override.CoordinateOverrideAdminService;
import com.home.domain.coordinate.CoordinateIdentityBlockingPolicy;
import com.home.infrastructure.persistence.complex.JdbcComplexRelationRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
		ObjectProvider<BuildingFootprintSource> buildingFootprintSourceProvider,
		@Value("${complex.coordinate.identity.block-on-unavailable:true}") boolean blockOnUnavailableIdentity,
		@Value("${complex.coordinate.identity.block-on-failed:true}") boolean blockOnFailedIdentity
	) {
		JdbcClient jdbcClient = requiredJdbcClient(jdbcClientProvider);
		return new ComplexCoordinateExceptionService(
			new JdbcComplexCoordinateExceptionRepository(jdbcClient),
			new JdbcComplexRelationRepository(jdbcClient),
			new ComplexRelationClassifier(),
			identityVerifierProvider.getIfAvailable(ComplexCoordinateIdentityVerifier::trusting),
			buildingFootprintSourceProvider.getIfAvailable(BuildingFootprintSource::unavailable),
			new CoordinateIdentityBlockingPolicy(blockOnUnavailableIdentity, blockOnFailedIdentity)
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
	CoordinateOverrideAdminRepository coordinateOverrideAdminRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return new JdbcCoordinateOverrideAdminRepository(
			requiredJdbcClient(jdbcClientProvider),
			new TransactionTemplate(requiredTransactionManager(transactionManagerProvider))
		);
	}

	@Bean
	@Lazy
	CoordinateOverrideAdminService coordinateOverrideAdminService(
		CoordinateOverrideAdminRepository repository
	) {
		return new CoordinateOverrideAdminService(repository);
	}

	@Bean
	@Lazy
	ComplexCoordinateReadinessService complexCoordinateReadinessService(
		ComplexCoordinateExceptionService complexCoordinateExceptionService,
		ComplexCoordinateReadinessRepository complexCoordinateReadinessRepository,
		ComplexDisplayCoordinateProjectionService complexDisplayCoordinateProjectionService,
		@Value("${home.coordinate.readiness.retry-limit:200}") int retryLimit,
		@Value("${home.coordinate.readiness.retry-after-millis:21600000}") long retryAfterMillis
	) {
		return new ComplexCoordinateReadinessService(
			complexCoordinateExceptionService,
			complexCoordinateReadinessRepository,
			complexDisplayCoordinateProjectionService,
			retryLimit,
			java.time.Duration.ofMillis(retryAfterMillis)
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

	private PlatformTransactionManager requiredTransactionManager(
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return transactionManagerProvider.getIfAvailable(() -> {
			throw new IllegalStateException("PlatformTransactionManager is required for complex coordinate persistence");
		});
	}

}
