package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.home.application.coordinate.lookup.CoordinateSourceFirstParcelCoordinateResolver;
import com.home.application.coordinate.lookup.ParcelCoordinate;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VworldExternalApiConfigurationTest {

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 Coordinate Source DB miss에서 VWorld fallback을 호출하지 않는다")
	void primaryCoordinateResolverDoesNotCallVworldFallbackOnCoordinateSourceMiss() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.empty()
		);

		assertThat(resolver).isInstanceOf(CoordinateSourceFirstParcelCoordinateResolver.class);
		assertThat(resolver.resolve("1168010300107770001")).isEmpty();
	}

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 approved override를 사용하고 VWorld fallback은 호출하지 않는다")
	void primaryCoordinateResolverUsesApprovedOverrideWithoutVworldFallback() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		ParcelCoordinate override = new ParcelCoordinate(
			new java.math.BigDecimal("37.6012345"),
			new java.math.BigDecimal("127.1543210")
		);
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.of(override)
		);

		assertThat(resolver.resolve("1168010300107770001")).contains(override);
	}

	@Test
	@DisplayName("일반 RTMS bootstrap coordinate resolver는 VWorld fallback property를 받지 않는다")
	void primaryCoordinateResolverDoesNotExposeVworldFallbackSwitch() throws NoSuchMethodException {
		VworldExternalApiConfiguration.class.getDeclaredMethod(
			"parcelCoordinateResolver",
			com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository.class,
			com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository.class
		);
	}
}
