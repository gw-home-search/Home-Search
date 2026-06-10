package com.home.application.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.map.ComplexMarkerResult;
import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.RegionMarkerResult;
import com.home.application.map.RegionMarkerQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapQueryUseCaseTest {

	private final ComplexMarkerQuery complexRequest = new ComplexMarkerQuery(
		37.45,
		126.85,
		37.70,
		127.20,
		null,
		null,
		null,
		null,
		null,
		null,
		null,
		null
	);
	private final RegionMarkerQuery regionRequest = new RegionMarkerQuery(
		37.45,
		126.85,
		37.70,
		127.20,
		"si-gun-gu"
	);

	@Test
	@DisplayName("map query use case는 complex/region marker lookup을 위임한다")
	void delegatesComplexAndRegionMarkerLookups() {
		var complexMarkers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var regionMarkers = List.of(new RegionMarkerResult(11L, "Gangnam-gu", 37.5172, 127.0473, null, 1200L));
		var complexRepository = new CapturingComplexMarkerRepository(complexMarkers);
		var regionRepository = new CapturingRegionMarkerRepository(regionMarkers);
		MapQueryUseCase useCase = new MapQueryUseCase(complexRepository, regionRepository);

		assertThat(useCase.getComplexMarkers(complexRequest)).isSameAs(complexMarkers);
		assertThat(useCase.getRegionMarkers(regionRequest)).isSameAs(regionMarkers);
		assertThat(complexRepository.request).isSameAs(complexRequest);
		assertThat(regionRepository.request).isSameAs(regionRequest);
	}

	private static class CapturingComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<ComplexMarkerResult> markers;
		private ComplexMarkerQuery request;

		CapturingComplexMarkerRepository(List<ComplexMarkerResult> markers) {
			this.markers = markers;
		}

		@Override
		public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery request) {
			this.request = request;
			return markers;
		}
	}

	private static class CapturingRegionMarkerRepository implements RegionMarkerRepository {

		private final List<RegionMarkerResult> markers;
		private RegionMarkerQuery request;

		CapturingRegionMarkerRepository(List<RegionMarkerResult> markers) {
			this.markers = markers;
		}

		@Override
		public List<RegionMarkerResult> findRegionMarkers(RegionMarkerQuery request) {
			this.request = request;
			return markers;
		}
	}
}
