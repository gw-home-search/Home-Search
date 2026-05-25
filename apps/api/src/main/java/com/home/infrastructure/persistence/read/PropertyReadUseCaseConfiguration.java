package com.home.infrastructure.persistence.read;

import com.home.application.read.EmptyPropertyReadRepository;
import com.home.application.read.PropertyReadRepository;
import com.home.application.read.PropertyReadUseCase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PropertyReadUseCaseConfiguration {

	@Bean
	PropertyReadUseCase propertyReadUseCase(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable();
		PropertyReadRepository repository = jdbcClient == null
			? new EmptyPropertyReadRepository()
			: new JdbcPropertyReadRepository(jdbcClient);
		return new PropertyReadUseCase(repository);
	}
}
