package com.home.infrastructure.persistence.region;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRegionSiGunGuCodeReaderTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("region LAWD 코드 reader는 세종을 포함한 전국 5자리 코드를 중복 없이 정렬해 반환한다")
    void readsNationwideLawdCodes() {
        var flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
        jdbcClient = JdbcClient.create(dataSource);

        List<String> codes = new JdbcRegionSiGunGuCodeReader(() -> jdbcClient).siGunGuCodes();

        assertThat(codes).hasSizeGreaterThan(200);
        assertThat(codes).allMatch(code -> code.matches("\\d{5}"));
        assertThat(codes).contains("36110");
        assertThat(codes).isSorted();
        assertThat(codes).doesNotHaveDuplicates();
    }
}
