package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.home.infrastructure.persistence.ingest.ParcelCoordinateResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VworldExternalApiConfigurationTest {

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 snapshot miss에서 VWorld fallback을 호출하지 않는다")
	void primaryCoordinateResolverDoesNotCallVworldFallbackOnSnapshotMiss() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.empty()
		);

		assertThat(resolver.resolve("1168010300107770001", null)).isEmpty();
	}
}
