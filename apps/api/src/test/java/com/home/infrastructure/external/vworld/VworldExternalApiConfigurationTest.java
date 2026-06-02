package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.home.infrastructure.persistence.ingest.ParcelCoordinate;
import com.home.infrastructure.persistence.ingest.ParcelCoordinateResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VworldExternalApiConfigurationTest {

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 snapshot miss에서 VWorld fallback을 호출하지 않는다")
	void primaryCoordinateResolverDoesNotCallVworldFallbackOnSnapshotMiss() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		AtomicBoolean fallbackCalled = new AtomicBoolean(false);
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.empty(),
			(pnu, item) -> {
				fallbackCalled.set(true);
				return Optional.of(new ParcelCoordinate(new BigDecimal("37.5012345"), new BigDecimal("127.0543210")));
			},
			false
		);

		assertThat(resolver.resolve("1168010300107770001", null)).isEmpty();
		assertThat(fallbackCalled).isFalse();
	}

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 approved override를 VWorld fallback보다 먼저 사용한다")
	void primaryCoordinateResolverUsesApprovedOverrideBeforeVworldFallback() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		ParcelCoordinate override = new ParcelCoordinate(new BigDecimal("37.6012345"), new BigDecimal("127.1543210"));
		AtomicBoolean fallbackCalled = new AtomicBoolean(false);
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.of(override),
			(pnu, item) -> {
				fallbackCalled.set(true);
				return Optional.of(new ParcelCoordinate(new BigDecimal("37.5012345"), new BigDecimal("127.0543210")));
			},
			true
		);

		assertThat(resolver.resolve("1168010300107770001", null)).contains(override);
		assertThat(fallbackCalled).isFalse();
	}

	@Test
	@DisplayName("RTMS ingest용 primary coordinate resolver는 opt-in이면 snapshot miss에서 VWorld fallback을 호출한다")
	void primaryCoordinateResolverCallsVworldFallbackWhenEnabled() {
		VworldExternalApiConfiguration configuration = new VworldExternalApiConfiguration();
		ParcelCoordinate fallback = new ParcelCoordinate(new BigDecimal("37.5012345"), new BigDecimal("127.0543210"));
		ParcelCoordinateResolver resolver = configuration.parcelCoordinateResolver(
			pnu -> Optional.empty(),
			pnu -> Optional.empty(),
			(pnu, item) -> Optional.of(fallback),
			true
		);

		assertThat(resolver.resolve("1168010300107770001", null)).contains(fallback);
	}
}
