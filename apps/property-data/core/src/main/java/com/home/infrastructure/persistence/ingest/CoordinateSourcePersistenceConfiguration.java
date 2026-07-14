package com.home.infrastructure.persistence.ingest;

import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;
import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.infrastructure.persistence.ingest.coordinate.CoordinateSourceDbProperties;
import com.home.infrastructure.persistence.ingest.coordinate.JdbcCoordinateSourceParcelCoordinateRepository;
import com.home.infrastructure.persistence.ingest.coordinate.JdbcParcelCoordinateOverrideRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoordinateSourceDbProperties.class)
class CoordinateSourcePersistenceConfiguration {

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
    ParcelCoordinateOverrideRepository parcelCoordinateOverrideRepository(JdbcClient jdbcClient) {
        return new JdbcParcelCoordinateOverrideRepository(jdbcClient);
    }
}
