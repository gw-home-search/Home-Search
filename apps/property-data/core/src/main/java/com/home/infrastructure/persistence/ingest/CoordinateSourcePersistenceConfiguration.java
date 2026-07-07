package com.home.infrastructure.persistence.ingest;

import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;
import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.infrastructure.persistence.ingest.coordinate.CoordinateSourceDbProperties;
import com.home.infrastructure.persistence.ingest.coordinate.JdbcCoordinateSourceParcelCoordinateRepository;
import com.home.infrastructure.persistence.ingest.coordinate.JdbcParcelCoordinateOverrideRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
class CoordinateSourcePersistenceConfiguration {

	@Bean
	@Lazy
	CoordinateSourceDbProperties coordinateSourceDbProperties(
		@Value("${home.coordinate-source.db.jdbc-url:${COORDINATE_SOURCE_DB_JDBC_URL:}}") String jdbcUrl,
		@Value("${home.coordinate-source.db.username:${COORDINATE_SOURCE_DB_USERNAME:${DB_USERNAME:}}}") String username,
		@Value("${home.coordinate-source.db.password:${COORDINATE_SOURCE_DB_PASSWORD:${DB_PASSWORD:}}}") String password,
		@Value("${home.coordinate-source.db.connect-timeout-seconds:${COORDINATE_SOURCE_DB_CONNECT_TIMEOUT_SECONDS:5}}")
		int connectTimeoutSeconds,
		@Value("${home.coordinate-source.db.socket-timeout-seconds:${COORDINATE_SOURCE_DB_SOCKET_TIMEOUT_SECONDS:10}}")
		int socketTimeoutSeconds,
		@Value("${home.coordinate-source.db.lock-timeout-millis:${COORDINATE_SOURCE_DB_LOCK_TIMEOUT_MILLIS:1000}}")
		int lockTimeoutMillis,
		@Value("${home.coordinate-source.db.statement-timeout-millis:${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:3000}}")
		int statementTimeoutMillis,
		@Value("${home.coordinate-source.db.read-only:${COORDINATE_SOURCE_DB_READ_ONLY:true}}") boolean readOnly
	) {
		return new CoordinateSourceDbProperties(
			jdbcUrl,
			username,
			password,
			connectTimeoutSeconds,
			socketTimeoutSeconds,
			lockTimeoutMillis,
			statementTimeoutMillis,
			readOnly
		);
	}

	@Bean
	@Lazy
	ParcelCoordinateSourceRepository parcelCoordinateSourceRepository(CoordinateSourceDbProperties properties) {
		if (!properties.enabled()) {
			return ParcelCoordinateSourceRepository.empty();
		}
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.postgresql.Driver");
		dataSource.setUrl(properties.jdbcUrl());
		dataSource.setUsername(properties.username());
		dataSource.setPassword(properties.password());
		dataSource.setConnectionProperties(properties.connectionProperties());
		return new JdbcCoordinateSourceParcelCoordinateRepository(JdbcClient.create(dataSource));
	}

	@Bean
	@Lazy
	ParcelCoordinateOverrideRepository parcelCoordinateOverrideRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcParcelCoordinateOverrideRepository(
			IngestPersistenceJdbcSupport.requiredJdbcClient(jdbcClientProvider)
		);
	}
}
