package com.home.infrastructure.persistence.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRegionSiGunGuCodeReaderTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("region 시군구 코드 reader는 전국 시군구 5자리 코드를 중복 없이 정렬해 반환한다")
	void readsNationwideSiGunGuCodes() {
		var flyway = flyway(null);
		flyway.clean();
		flyway.migrate();
		jdbcClient = JdbcClient.create(dataSource);

		List<String> codes = new JdbcRegionSiGunGuCodeReader(() -> jdbcClient).siGunGuCodes();

		assertThat(codes).hasSizeGreaterThan(200);
		assertThat(codes).allMatch(code -> code.matches("\\d{5}"));
		assertThat(codes).isSorted();
		assertThat(codes).doesNotHaveDuplicates();
	}
}
