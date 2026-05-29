package com.home.infrastructure.persistence.map;

import com.home.application.map.MapQueryUseCase;
import com.home.application.map.MapUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class MapUseCaseConfiguration {

	@Bean
	@ConditionalOnMissingBean(MapUseCase.class)
	MapUseCase mapUseCase(ObjectProvider<JdbcClient> jdbcClientProvider) {
		JdbcClient jdbcClient = requiredJdbcClient(jdbcClientProvider);
		return new MapQueryUseCase(
			new JdbcMapMarkerRepository(jdbcClient),
			new JdbcRegionMarkerRepository(jdbcClient)
		);
	}

	private JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for map read persistence");
		});
	}
}
