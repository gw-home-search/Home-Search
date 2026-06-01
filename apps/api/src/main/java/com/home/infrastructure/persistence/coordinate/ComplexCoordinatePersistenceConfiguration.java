package com.home.infrastructure.persistence.coordinate;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.coordinate.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.ComplexCoordinateExceptionService;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.persistence.complex.JdbcComplexRelationRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ComplexCoordinatePersistenceConfiguration {

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexCoordinateExceptionRepository complexCoordinateExceptionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexCoordinateExceptionRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexCoordinateExceptionService complexCoordinateExceptionService(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = requiredJdbcClient(jdbcClientProvider);
		return new ComplexCoordinateExceptionService(
			new JdbcComplexCoordinateExceptionRepository(jdbcClient),
			new JdbcComplexRelationRepository(jdbcClient),
			new ComplexRelationClassifier()
		);
	}

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexDisplayCoordinateProjectionRepository complexDisplayCoordinateProjectionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcComplexDisplayCoordinateProjectionRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexDisplayCoordinateProjectionService complexDisplayCoordinateProjectionService(
		ComplexDisplayCoordinateProjectionRepository repository
	) {
		return new ComplexDisplayCoordinateProjectionService(repository);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for complex coordinate persistence");
		});
	}
}
