package com.home.infrastructure.persistence.complex;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationRepository;
import com.home.application.complex.ComplexRelationUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ComplexRelationPersistenceConfiguration {

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexRelationUseCase complexRelationUseCase(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for complex relation persistence");
		});
		ComplexRelationRepository repository = new JdbcComplexRelationRepository(jdbcClient);
		return new ComplexRelationUseCase(repository, new ComplexRelationClassifier());
	}
}
