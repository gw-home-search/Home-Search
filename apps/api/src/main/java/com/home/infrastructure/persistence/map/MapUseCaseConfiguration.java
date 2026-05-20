package com.home.infrastructure.persistence.map;

import com.home.application.map.EmptyMapUseCase;
import com.home.application.map.MapQueryUseCase;
import com.home.application.map.MapUseCase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class MapUseCaseConfiguration {

	@Bean
	MapUseCase mapUseCase(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = jdbcClientProvider.getIfAvailable();
		if (jdbcClient == null) {
			return new EmptyMapUseCase();
		}
		return new MapQueryUseCase(
			new JdbcMapMarkerRepository(jdbcClient),
			new JdbcRegionMarkerRepository(jdbcClient)
		);
	}
}
