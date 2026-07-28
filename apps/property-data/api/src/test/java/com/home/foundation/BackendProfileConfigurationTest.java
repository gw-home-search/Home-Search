package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class BackendProfileConfigurationTest {

    @Test
    @DisplayName("base profile은 database auto-configuration과 actuator exposure를 profile scope로 유지한다")
    void baseProfileDoesNotGloballyDisableDatabaseAutoConfiguration() throws IOException {
        Properties properties = load("application.yml");

        assertThat(properties.getProperty("spring.autoconfigure.exclude")).isNull();
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("spring.flyway.locations")).isNull();
        assertThat(properties.getProperty("spring.profiles.default")).isEqualTo("local");
        assertThat(properties.getProperty("home.news.public.enabled")).isEqualTo("${HOME_NEWS_PUBLIC_ENABLED:false}");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isNull();
        assertThat(properties.getProperty("management.prometheus.metrics.export.enabled"))
                .isNull();
    }

    @Test
    @DisplayName("test profile만 database auto-configuration을 비활성화한다")
    void testProfileDisablesDatabaseAutoConfiguration() throws IOException {
        Properties properties = load("application-test.yml");

        assertThat(properties.getProperty("spring.autoconfigure.exclude"))
                .contains("DataSourceAutoConfiguration")
                .contains("FlywayAutoConfiguration");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
        assertThat(properties.getProperty("management.prometheus.metrics.export.enabled"))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("local profile은 PostgreSQL만 연결하고 Flyway 자동 실행 우회 설정을 제공하지 않는다")
    void localProfileWiresPostgresWithoutFlywayAutoMigrationOverrides() throws IOException {
        Properties properties = load("application-local.yml");

        assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DB_JDBC_URL}");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(properties.stringPropertyNames()).noneMatch(name -> name.startsWith("spring.flyway."));
        assertThat(properties.getProperty("home.coordinate-source.db.jdbc-url"))
                .isEqualTo("${COORDINATE_SOURCE_DB_JDBC_URL:}");
        assertThat(properties.getProperty("home.coordinate-source.db.username"))
                .isEqualTo("${COORDINATE_SOURCE_DB_USERNAME}");
        assertThat(properties.getProperty("home.coordinate-source.db.password"))
                .isEqualTo("${COORDINATE_SOURCE_DB_PASSWORD}");
        assertThat(properties.getProperty("home.coordinate-source.db.connect-timeout-seconds"))
                .isEqualTo("${COORDINATE_SOURCE_DB_CONNECT_TIMEOUT_SECONDS:5}");
        assertThat(properties.getProperty("home.coordinate-source.db.socket-timeout-seconds"))
                .isEqualTo("${COORDINATE_SOURCE_DB_SOCKET_TIMEOUT_SECONDS:10}");
        assertThat(properties.getProperty("home.coordinate-source.db.lock-timeout-millis"))
                .isEqualTo("${COORDINATE_SOURCE_DB_LOCK_TIMEOUT_MILLIS:1000}");
        assertThat(properties.getProperty("home.coordinate-source.db.statement-timeout-millis"))
                .isEqualTo("${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:3000}");
        assertThat(properties.getProperty("home.coordinate-source.db.read-only"))
                .isEqualTo("${COORDINATE_SOURCE_DB_READ_ONLY:true}");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
        assertThat(properties.getProperty("management.prometheus.metrics.export.enabled"))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("staging과 prod profile은 coordinate source 없이 기동 가능한 production-safe 기본값을 유지한다")
    void runtimeProfilesRequireDatabaseCredentialsAndKeepOptionalFeaturesDisabled() {
        assertThat(List.of("staging", "prod")).allSatisfy(profile -> {
            Properties properties = load("application-" + profile + ".yml");

            assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DB_JDBC_URL}");
            assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
            assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
            assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(properties.getProperty("home.news.public.enabled"))
                    .isEqualTo("${HOME_NEWS_PUBLIC_ENABLED:false}");
            assertThat(properties.getProperty("home.admin.internal.public-keys"))
                    .isEqualTo("${HOME_ADMIN_INTERNAL_PUBLIC_KEYS}");
            assertThat(properties.getProperty("home.coordinate-source.db.jdbc-url"))
                    .isEqualTo("${COORDINATE_SOURCE_DB_JDBC_URL:}");
            assertThat(properties.getProperty("home.coordinate-source.db.username"))
                    .isEqualTo("${COORDINATE_SOURCE_DB_USERNAME:}");
            assertThat(properties.getProperty("home.coordinate-source.db.password"))
                    .isEqualTo("${COORDINATE_SOURCE_DB_PASSWORD:}");
            assertThat(properties.getProperty("home.coordinate-source.db.read-only"))
                    .isEqualTo("${COORDINATE_SOURCE_DB_READ_ONLY:true}");
            assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health,prometheus");
        });
    }

    private Properties load(String fileName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(fileName));
        return factory.getObject();
    }
}
