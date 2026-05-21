package com.home.infrastructure.persistence.read;

import com.home.application.read.EmptyMvpReadRepository;
import com.home.application.read.MvpReadRepository;
import com.home.application.read.MvpReadUseCase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class MvpReadUseCaseConfiguration {

	@Bean
	MvpReadUseCase mvpReadUseCase(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable();
		MvpReadRepository repository = jdbcClient == null
			? new EmptyMvpReadRepository()
			: new JdbcMvpReadRepository(jdbcClient);
		return new MvpReadUseCase(repository);
	}
}
