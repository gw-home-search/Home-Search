package com.home.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class AdminMigrationIntegrationTest {
    @Test
    void packagedRunnerMigratesAConfirmedFreshAdminDatabase() throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("home_search_admin")
                .withUsername("admin_test")
                .withPassword("admin_test")) {
            database.start();
            AdminMigrationRunner runner = new AdminMigrationRunner(
                    new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));

            runner.run(new DefaultApplicationArguments("--operation=migrate", "--target=1", "--confirm=1"));

            assertThat(runner.getExitCode()).isZero();
            try (var connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                assertThat(count(
                                connection,
                                "SELECT count(*) FROM admin.flyway_schema_history WHERE version='1' AND success"))
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void versionOneCreatesOnlyAdminSecurityStateWithoutAccountsOrSecrets() throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("home_search_admin")
                .withUsername("admin_test")
                .withPassword("admin_test")) {
            database.start();
            try (var connection = DriverManager.getConnection(
                            database.getJdbcUrl(), database.getUsername(), database.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE home_search_admin_runtime LOGIN PASSWORD 'runtime-test-password'");
            }
            Flyway flyway = Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration/admin")
                    .schemas("admin")
                    .defaultSchema("admin")
                    .table("flyway_schema_history")
                    .load();

            flyway.migrate();
            flyway.validate();

            try (var connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                assertThat(count(connection, "SELECT count(*) FROM admin.admin_account"))
                        .isZero();
                assertThat(count(connection, "SELECT count(*) FROM admin.admin_role"))
                        .isEqualTo(3);
                assertThat(count(connection, "SELECT count(*) FROM admin.admin_permission"))
                        .isEqualTo(8);
                assertThat(count(
                                connection,
                                "SELECT count(*) FROM admin.migration_ownership WHERE owner_name='admin-service'"))
                        .isEqualTo(1);
                assertThat(count(
                                connection,
                                "SELECT count(*) FROM admin.flyway_schema_history WHERE version='1' AND success"))
                        .isEqualTo(1);
            }
            try (var runtime = DriverManager.getConnection(
                    database.getJdbcUrl(), "home_search_admin_runtime", "runtime-test-password")) {
                assertThat(count(runtime, "SELECT count(*) FROM admin.admin_role"))
                        .isEqualTo(3);
                assertThatThrownBy(() -> runtime.createStatement()
                                .execute("CREATE TABLE admin.runtime_must_not_create(id integer)"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> runtime.createStatement()
                                .execute("UPDATE admin.flyway_schema_history SET description=description WHERE false"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    private long count(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
