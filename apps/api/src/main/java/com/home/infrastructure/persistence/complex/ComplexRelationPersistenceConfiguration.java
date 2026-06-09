package com.home.infrastructure.persistence.complex;

import com.home.domain.complex.relation.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationCaseRepository;
import com.home.application.complex.ComplexRelationCaseService;
import com.home.application.complex.ComplexRelationRepository;
import com.home.application.complex.ComplexRelationUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexRelationCaseRepository complexRelationCaseRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return new JdbcComplexRelationCaseRepository(
			requiredJdbcClient(jdbcClientProvider),
			new TransactionTemplate(requiredTransactionManager(transactionManagerProvider))
		);
	}

	@Bean
	@ConditionalOnBean(JdbcClient.class)
	ComplexRelationCaseService complexRelationCaseService(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ComplexRelationCaseRepository complexRelationCaseRepository,
		@Value("${home.complex.relation.classifier-version:relation-classifier-stable}") String classifierVersion
	) {
		return new ComplexRelationCaseService(
			new JdbcComplexRelationRepository(requiredJdbcClient(jdbcClientProvider)),
			new ComplexRelationClassifier(),
			complexRelationCaseRepository,
			classifierVersion
		);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for complex relation persistence");
		});
	}

	private PlatformTransactionManager requiredTransactionManager(
		ObjectProvider<PlatformTransactionManager> transactionManagerProvider
	) {
		return transactionManagerProvider.getIfAvailable(() -> {
			throw new IllegalStateException("PlatformTransactionManager is required for complex relation persistence");
		});
	}
}
