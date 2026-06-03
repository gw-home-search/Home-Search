package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsCoordinateSourcePreflightTest {

	@Test
	@DisplayName("coordinate source URL이 없으면 RTMS ingest preflight는 실패한다")
	void missingCoordinateSourceUrlFailsPreflight() {
		RtmsCoordinateSourceAvailabilityProbe probe = mock(RtmsCoordinateSourceAvailabilityProbe.class);
		RtmsCoordinateSourcePreflight preflight = new RequiredRtmsCoordinateSourcePreflight(" ", false, probe);

		assertThatThrownBy(preflight::verify)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("COORDINATE_SOURCE_DB_JDBC_URL")
			.hasMessageContaining("HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=true");
		verifyNoInteractions(probe);
	}

	@Test
	@DisplayName("coordinate-pending only 허용이 명시되면 coordinate source probe를 건너뛴다")
	void explicitCoordinatePendingOnlyModeSkipsProbe() {
		RtmsCoordinateSourceAvailabilityProbe probe = mock(RtmsCoordinateSourceAvailabilityProbe.class);
		RtmsCoordinateSourcePreflight preflight = new RequiredRtmsCoordinateSourcePreflight(" ", true, probe);

		preflight.verify();

		verifyNoInteractions(probe);
	}

	@Test
	@DisplayName("coordinate source URL이 있으면 source DB availability를 검증한다")
	void configuredCoordinateSourceRunsAvailabilityProbe() {
		RtmsCoordinateSourceAvailabilityProbe probe = mock(RtmsCoordinateSourceAvailabilityProbe.class);
		RtmsCoordinateSourcePreflight preflight = new RequiredRtmsCoordinateSourcePreflight(
			"jdbc:postgresql://localhost:15432/source",
			false,
			probe
		);

		preflight.verify();

		verify(probe).verifyAvailable();
	}
}
