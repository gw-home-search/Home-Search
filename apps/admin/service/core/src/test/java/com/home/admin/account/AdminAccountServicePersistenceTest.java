package com.home.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

class AdminAccountServicePersistenceTest {
    @Test
    void protectsLastAdminAndRevokesSessionWhenRolesChange() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("home_search_admin").withUsername("admin_test").withPassword("admin_test")) {
            database.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/admin")
                .schemas("admin").defaultSchema("admin").table("flyway_schema_history").load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            UUID first = account(jdbc, "first-admin", "ADMIN");
            var service = new AdminAccountService(jdbc, new BCryptPasswordEncoder(12));

            assertThatThrownBy(() -> service.setEnabled(first, first, false))
                .isInstanceOf(AdminAccountService.CannotRemoveLastAdminException.class);
            assertThat(jdbc.sql("SELECT enabled FROM admin.admin_account WHERE id=:id").param("id", first).query(Boolean.class).single()).isTrue();

            account(jdbc, "second-admin", "ADMIN");
            insertSession(jdbc, "first-admin");
            service.replaceRoles(first, first, Set.of("VIEWER"));

            assertThat(jdbc.sql("SELECT role_code FROM admin.admin_account_role WHERE account_id=:id").param("id", first).query(String.class).single()).isEqualTo("VIEWER");
            assertThat(jdbc.sql("SELECT count(*) FROM admin.spring_session WHERE principal_name='first-admin'").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM admin.admin_security_audit_event WHERE event_type='ROLES_CHANGED'").query(Integer.class).single()).isEqualTo(1);
        }
    }

    private UUID account(JdbcClient jdbc, String loginId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO admin.admin_account(id,login_id,display_name,password_hash) VALUES (:id,:loginId,:loginId,'test-hash')")
            .param("id", id).param("loginId", loginId).update();
        jdbc.sql("INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role)")
            .param("id", id).param("role", role).update();
        return id;
    }

    private void insertSession(JdbcClient jdbc, String loginId) {
        long now = Instant.now().toEpochMilli();
        jdbc.sql("""
            INSERT INTO admin.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
            VALUES ('primary-role-test','browser-role-test',:now,:now,1800,:expiry,:loginId)
            """).param("now", now).param("expiry", now + 1_800_000).param("loginId", loginId).update();
    }
}
