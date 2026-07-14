package com.home.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class UserSchemaMigrationTest {
    @Test
    void migratesFreshDatabaseAndKeepsRuntimeAwayFromDdlAndHistory() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (var admin = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = admin.createStatement()) {
                admin.setAutoCommit(true);
                statement.execute("CREATE ROLE home_search_user_migrator LOGIN PASSWORD 'migrator-test-only'");
                statement.execute("CREATE ROLE home_search_user_runtime LOGIN PASSWORD 'runtime-test-only'");
                statement.execute("CREATE DATABASE home_search_user OWNER home_search_user_migrator");
            }
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                    + "/home_search_user";
            Flyway flyway = Flyway.configure()
                    .dataSource(url, "home_search_user_migrator", "migrator-test-only")
                    .locations(System.getProperty("userServiceMigrationLocation"))
                    .schemas("users")
                    .defaultSchema("users")
                    .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            long userId;
            try (var migrator = DriverManager.getConnection(url, "home_search_user_migrator", "migrator-test-only")) {
                try (var result = migrator.createStatement()
                        .executeQuery(
                                "INSERT INTO users.user_account(role,display_name,created_at,updated_at) VALUES('USER','favorite-test',now(),now()) RETURNING id")) {
                    result.next();
                    userId = result.getLong(1);
                }
            }
            try (var runtime = DriverManager.getConnection(url, "home_search_user_runtime", "runtime-test-only")) {
                runtime.createStatement()
                        .execute("INSERT INTO users.favorite_complex(user_id,complex_id,saved_at) VALUES(" + userId
                                + ",501,now())");
                assertThat(runtime.createStatement()
                                .executeQuery("SELECT complex_id FROM users.favorite_complex WHERE user_id=" + userId)
                                .next())
                        .isTrue();
                assertThatThrownBy(() -> runtime.createStatement()
                                .execute("UPDATE users.favorite_complex SET saved_at=now() WHERE user_id=" + userId))
                        .hasMessageContaining("permission denied");
                runtime.createStatement().execute("DELETE FROM users.favorite_complex WHERE user_id=" + userId);
                assertThatThrownBy(() -> runtime.createStatement()
                                .execute("CREATE TABLE users.runtime_must_not_create(id bigint)"))
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() ->
                                runtime.createStatement().executeQuery("SELECT * FROM users.flyway_schema_history"))
                        .hasMessageContaining("permission denied");
            }
            try (var migrator = DriverManager.getConnection(url, "home_search_user_migrator", "migrator-test-only")) {
                migrator.createStatement()
                        .execute("INSERT INTO users.favorite_complex(user_id,complex_id,saved_at) VALUES(" + userId
                                + ",777,now())");
                migrator.createStatement().execute("DELETE FROM users.user_account WHERE id=" + userId);
                var result = migrator.createStatement()
                        .executeQuery("SELECT count(*) FROM users.favorite_complex WHERE user_id=" + userId);
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }
}
