package com.home.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

class AdminOpsIntegrationTest {
    @Test
    void createsOnlyOneInitialAdminAndRevokesSessionOnPasswordChange() throws Exception {
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
            var runner = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "initial-password");

            runner.run(args("--operation=create-initial-admin", "--login-id=operator", "--display-name=운영자"));

            assertThat(runner.getExitCode()).isZero();
            assertThat(value(jdbc, "SELECT count(*) FROM admin.admin_account WHERE enabled", Integer.class))
                    .isEqualTo(1);
            assertThat(value(jdbc, "SELECT role_code FROM admin.admin_account_role", String.class))
                    .isEqualTo("ADMIN");
            assertThat(new BCryptPasswordEncoder(12)
                            .matches(
                                    "initial-password",
                                    value(
                                            jdbc,
                                            "SELECT password_hash FROM admin.admin_account WHERE login_id='operator'",
                                            String.class)))
                    .isTrue();

            var disableLastAdmin = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "unused-password");
            disableLastAdmin.run(args("--operation=disable-account", "--login-id=operator"));
            assertThat(disableLastAdmin.getExitCode()).isEqualTo(2);
            assertThat(value(jdbc, "SELECT enabled FROM admin.admin_account WHERE login_id='operator'", Boolean.class))
                    .isTrue();

            var duplicate = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "other-password");
            duplicate.run(args("--operation=create-initial-admin", "--login-id=second", "--display-name=두번째"));
            assertThat(duplicate.getExitCode()).isEqualTo(2);
            assertThat(value(jdbc, "SELECT count(*) FROM admin.admin_account", Integer.class))
                    .isEqualTo(1);

            insertSession(jdbc, "operator", "operator");
            var passwordChange = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "changed-password");
            passwordChange.run(args("--operation=set-password", "--login-id=operator"));
            assertThat(passwordChange.getExitCode()).isZero();
            assertThat(value(
                            jdbc,
                            "SELECT count(*) FROM admin.spring_session WHERE principal_name='operator'",
                            Integer.class))
                    .isZero();
            assertThat(new BCryptPasswordEncoder(12)
                            .matches(
                                    "changed-password",
                                    value(
                                            jdbc,
                                            "SELECT password_hash FROM admin.admin_account WHERE login_id='operator'",
                                            String.class)))
                    .isTrue();
            assertThat(value(jdbc, "SELECT count(*) FROM admin.admin_security_audit_event", Integer.class))
                    .isEqualTo(2);

            var createViewer = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "viewer-password");
            createViewer.run(
                    args("--operation=create-account", "--login-id=viewer", "--display-name=조회자", "--role=VIEWER"));
            assertThat(createViewer.getExitCode()).isZero();

            var grantRole = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "unused-password");
            grantRole.run(args("--operation=grant-role", "--login-id=viewer", "--role=OPERATOR"));
            assertThat(grantRole.getExitCode()).isZero();
            assertThat(value(
                            jdbc,
                            "SELECT count(*) FROM admin.admin_account_role ar JOIN admin.admin_account a ON a.id=ar.account_id WHERE a.login_id='viewer'",
                            Integer.class))
                    .isEqualTo(2);

            insertSession(jdbc, "viewer", "viewer");
            var revoke = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "unused-password");
            revoke.run(args("--operation=revoke-sessions", "--login-id=viewer"));
            assertThat(revoke.getExitCode()).isZero();
            assertThat(value(
                            jdbc,
                            "SELECT count(*) FROM admin.spring_session WHERE principal_name='viewer'",
                            Integer.class))
                    .isZero();

            var disable = new AdminOpsRunner(
                    dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "unused-password");
            disable.run(args("--operation=disable-account", "--login-id=viewer"));
            assertThat(disable.getExitCode()).isZero();
            assertThat(value(jdbc, "SELECT enabled FROM admin.admin_account WHERE login_id='viewer'", Boolean.class))
                    .isFalse();

            for (String[] invalid : new String[][] {
                {"--operation=create-account", "--login-id=third", "--display-name=세번째", "--role=UNKNOWN"},
                {"--operation=set-password", "--login-id=missing"},
                {"--operation=unknown"},
                {}
            }) {
                var invalidRunner = new AdminOpsRunner(
                        dataSource, jdbc, new DataSourceTransactionManager(dataSource), () -> "unused-password");
                invalidRunner.run(args(invalid));
                assertThat(invalidRunner.getExitCode()).isEqualTo(2);
            }
        }
    }

    private DefaultApplicationArguments args(String... values) {
        return new DefaultApplicationArguments(values);
    }

    private <T> T value(JdbcClient jdbc, String sql, Class<T> type) {
        return jdbc.sql(sql).query(type).single();
    }

    private void insertSession(JdbcClient jdbc, String loginId, String key) {
        long now = Instant.now().toEpochMilli();
        jdbc.sql("""
            INSERT INTO admin.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
            VALUES (:primaryId,:sessionId,:now,:now,1800,:expiry,:loginId)
            """)
                .param("primaryId", "primary-session-id-" + key)
                .param("sessionId", "browser-session-id-" + key)
                .param("now", now)
                .param("expiry", now + 1_800_000)
                .param("loginId", loginId)
                .update();
    }
}
