package com.home.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.admin.audit.AdminAuditService;
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
                .withDatabaseName("home_search_admin")
                .withUsername("admin_test")
                .withPassword("admin_test")) {
            database.start();
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/admin")
                    .schemas("admin")
                    .defaultSchema("admin")
                    .table("flyway_schema_history")
                    .load()
                    .migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            UUID first = account(jdbc, "first-admin", "ADMIN");
            var service = new AdminAccountService(jdbc, new BCryptPasswordEncoder(12));

            assertThatThrownBy(() -> service.setEnabled(first, first, false))
                    .isInstanceOf(AdminAccountService.CannotRemoveLastAdminException.class);
            assertThat(jdbc.sql("SELECT enabled FROM admin.admin_account WHERE id=:id")
                            .param("id", first)
                            .query(Boolean.class)
                            .single())
                    .isTrue();

            account(jdbc, "second-admin", "ADMIN");
            insertSession(jdbc, "first-admin");
            service.replaceRoles(first, first, Set.of("VIEWER"));

            assertThat(jdbc.sql("SELECT role_code FROM admin.admin_account_role WHERE account_id=:id")
                            .param("id", first)
                            .query(String.class)
                            .single())
                    .isEqualTo("VIEWER");
            assertThat(jdbc.sql("SELECT count(*) FROM admin.spring_session WHERE principal_name='first-admin'")
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM admin.admin_security_audit_event WHERE event_type='ROLES_CHANGED'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);

            var created = service.create(
                    first,
                    new AdminAccountService.CreateAccount(
                            "operator", "운영자", "long-enough-test-password", Set.of("OPERATOR")));
            assertThat(service.accounts())
                    .extracting(AdminAccountService.AccountSummary::loginId)
                    .contains("operator");
            insertSession(jdbc, "operator");
            service.setEnabled(first, created.accountId(), false);
            assertThat(jdbc.sql("SELECT enabled FROM admin.admin_account WHERE id=:id")
                            .param("id", created.accountId())
                            .query(Boolean.class)
                            .single())
                    .isFalse();

            insertSession(jdbc, "second-admin");
            UUID second = jdbc.sql("SELECT id FROM admin.admin_account WHERE login_id='second-admin'")
                    .query(UUID.class)
                    .single();
            service.revokeSessions(first, second);

            assertThatThrownBy(() -> service.create(
                            first, new AdminAccountService.CreateAccount("bad", "bad", "password", Set.of("UNKNOWN"))))
                    .isInstanceOf(AdminAccountService.InvalidRoleException.class);
            assertThatThrownBy(() -> service.revokeSessions(first, UUID.randomUUID()))
                    .isInstanceOf(AdminAccountService.AccountNotFoundException.class);

            var audit = new AdminAuditService(jdbc);
            audit.recordBffRequest(first, "request-1", "COORDINATE_READ", true);
            assertThat(audit.events(200, 0)).anySatisfy(event -> {
                assertThat(event.actorAccountId()).isEqualTo(first);
                assertThat(event.requestId()).isEqualTo("request-1");
                assertThat(event.success()).isTrue();
            });
            assertThatThrownBy(() -> audit.events(0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> audit.recordBffRequest(null, "", "", false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private UUID account(JdbcClient jdbc, String loginId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO admin.admin_account(id,login_id,display_name,password_hash) VALUES (:id,:loginId,:loginId,'test-hash')")
                .param("id", id)
                .param("loginId", loginId)
                .update();
        jdbc.sql("INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role)")
                .param("id", id)
                .param("role", role)
                .update();
        return id;
    }

    private void insertSession(JdbcClient jdbc, String loginId) {
        long now = Instant.now().toEpochMilli();
        jdbc.sql("""
            INSERT INTO admin.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
            VALUES (:primaryId,:sessionId,:now,:now,1800,:expiry,:loginId)
            """)
                .param("primaryId", "primary-" + loginId)
                .param("sessionId", "browser-" + loginId)
                .param("now", now)
                .param("expiry", now + 1_800_000)
                .param("loginId", loginId)
                .update();
    }
}
