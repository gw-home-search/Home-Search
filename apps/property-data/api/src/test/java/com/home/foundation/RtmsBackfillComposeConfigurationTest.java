package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsBackfillComposeConfigurationTest {

    private static final Path BACKFILL_COMPOSE = Path.of("..", "ops", "docker-compose.rtms-backfill.yml");
    private static final Path BATCH_BUILD = Path.of("..", "batch", "build.gradle");

    @Test
    @DisplayName("RTMS backfill Compose는 property runtime과 coordinate reader만 사용해 명시적 범위를 실행한다")
    void backfillComposeUsesLeastPrivilegeRuntimeCredentialsAndExplicitScope() throws IOException {
        assertThat(BACKFILL_COMPOSE).exists();

        String content = Files.readString(BACKFILL_COMPOSE);

        assertThat(content).contains("SPRING_BATCH_JOB_NAME: rtmsBackfillJob");
        assertThat(content).contains("fromYmd=${BATCH_FROM_YMD:?BATCH_FROM_YMD is required}");
        assertThat(content).contains("toYmd=${BATCH_TO_YMD:?BATCH_TO_YMD is required}");
        assertThat(content).contains("lawdCds=${BATCH_LAWD_CDS:?BATCH_LAWD_CDS is required}");
        assertThat(content).contains("requestId=${BATCH_REQUEST_ID:?BATCH_REQUEST_ID is required}");
        assertThat(content).contains("DB_USERNAME: ${DB_USERNAME:-home_search_property_runtime}");
        assertThat(content).contains("DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD is required}");
        assertThat(content).contains("APT_SERVICE_KEY: ${APT_SERVICE_KEY:?APT_SERVICE_KEY is required}");
        assertThat(content)
                .contains(
                        "COORDINATE_SOURCE_DB_USERNAME: ${COORDINATE_SOURCE_DB_USERNAME:-home_search_coordinate_reader}");
        assertThat(content)
                .contains(
                        "COORDINATE_SOURCE_DB_PASSWORD: ${COORDINATE_SOURCE_DB_PASSWORD:?COORDINATE_SOURCE_DB_PASSWORD is required}");
        assertThat(content).contains("HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY: \"false\"");
        assertThat(content).contains("HOME_OPS_HERMES_ENABLED: \"false\"");
        assertThat(content).contains("PROPERTY_DATA_BATCH_JAR: /app/property-data-batch.jar");
        assertThat(content).contains("./ops/run-batch-jar.sh:/app/run-batch-jar.sh:ro");
        assertThat(content).doesNotContain("- .:/workspace:ro");
        assertThat(content).contains("${PROPERTY_DATA_BATCH_JAR:?PROPERTY_DATA_BATCH_JAR is required}");
        assertThat(content).contains("external: true");
        assertThat(content).contains("name: home-search-local_home-search-local");
        assertThat(content).doesNotContain("HOME_SEARCH_DB_PASSWORD");
        assertThat(content).doesNotContain("PROPERTY_MIGRATOR_DB_PASSWORD");
        assertThat(content).doesNotContain("USER_RUNTIME_DB_PASSWORD");
        assertThat(content).doesNotContain("USER_MIGRATOR_DB_PASSWORD");
    }

    @Test
    @DisplayName("packaged Batch runtime은 JDBC auto-configuration을 포함한다")
    void packagedBatchRuntimeIncludesJdbcAutoConfiguration() throws IOException {
        assertThat(Files.readString(BATCH_BUILD))
                .contains("runtimeOnly 'org.springframework.boot:spring-boot-starter-jdbc'");
    }
}
