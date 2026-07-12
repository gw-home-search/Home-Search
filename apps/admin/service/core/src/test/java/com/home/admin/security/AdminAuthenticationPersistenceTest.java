package com.home.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

class AdminAuthenticationPersistenceTest {
    @Test
    void repeatedFailuresPersistLockAndAuditRows() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("home_search_admin").withUsername("admin_test").withPassword("admin_test")) {
            database.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/admin")
                .schemas("admin").defaultSchema("admin").table("flyway_schema_history").load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            UUID accountId = UUID.randomUUID();
            var encoder = new BCryptPasswordEncoder(12);
            jdbc.sql("INSERT INTO admin.admin_account(id,login_id,display_name,password_hash) VALUES (:id,'operator','운영자',:hash)")
                .param("id", accountId).param("hash", encoder.encode("correct-password")).update();
            var service = new AdminAuthenticationService(jdbc, encoder);
            ProxyFactory proxyFactory = new ProxyFactory(service);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
            AdminAuthenticationService transactionalService = (AdminAuthenticationService) proxyFactory.getProxy();

            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    transactionalService.authenticate("operator", "incorrect-password");
                } catch (RuntimeException expected) {
                    // Failure is the behavior under test; durable evidence is asserted below.
                }
            }

            assertThat(jdbc.sql("SELECT failed_login_count FROM admin.admin_account WHERE id=:id").param("id", accountId).query(Integer.class).single()).isEqualTo(5);
            assertThat(jdbc.sql("SELECT locked_until IS NOT NULL FROM admin.admin_account WHERE id=:id").param("id", accountId).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM admin.admin_security_audit_event WHERE event_type='LOGIN_FAILURE' AND NOT success").query(Integer.class).single()).isEqualTo(5);
        }
    }
}
