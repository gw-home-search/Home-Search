package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsCoordinateSourceAvailabilityProbeTest {

	@Test
	@DisplayName("jdbc URL이 비어 있으면 probe는 미구성 상태를 보고한다")
	void blankJdbcUrlReportsNotConfigured() {
		assertThat(probe(" ").configured()).isFalse();
		assertThat(probe(null).configured()).isFalse();
	}

	@Test
	@DisplayName("jdbc URL이 있으면 probe는 구성 상태를 보고한다")
	void presentJdbcUrlReportsConfigured() {
		assertThat(probe("jdbc:postgresql://localhost:15432/source").configured()).isTrue();
	}

	private JdbcRtmsCoordinateSourceAvailabilityProbe probe(String jdbcUrl) {
		return new JdbcRtmsCoordinateSourceAvailabilityProbe(jdbcUrl, "user", "secret", 5, 10, 1000, 3000);
	}
}
