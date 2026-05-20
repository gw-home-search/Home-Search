package com.home.application.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapQueryUseCaseTest {

	private final ComplexMarkersRequest complexRequest = new ComplexMarkersRequest(
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
	private final RegionMarkersRequest regionRequest = new RegionMarkersRequest(
		37.45,
		126.85,
		37.70,
		127.20,
		"si-gun-gu"
	);

	@Test
	@DisplayName("map query use case delegates complex and region marker lookups")
	void delegatesComplexAndRegionMarkerLookups() {
		var complexMarkers = List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L));
		var regionMarkers = List.of(new RegionMarkerResponse(11L, "Gangnam-gu", 37.5172, 127.0473, null));
		var complexRepository = new CapturingComplexMarkerRepository(complexMarkers);
		var regionRepository = new CapturingRegionMarkerRepository(regionMarkers);
		MapQueryUseCase useCase = new MapQueryUseCase(complexRepository, regionRepository);

		assertThat(useCase.getComplexMarkers(complexRequest)).isSameAs(complexMarkers);
		assertThat(useCase.getRegionMarkers(regionRequest)).isSameAs(regionMarkers);
		assertThat(complexRepository.request).isSameAs(complexRequest);
		assertThat(regionRepository.request).isSameAs(regionRequest);
	}

	private static class CapturingComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<ComplexMarkerResponse> markers;
		private ComplexMarkersRequest request;

		CapturingComplexMarkerRepository(List<ComplexMarkerResponse> markers) {
			this.markers = markers;
		}

		@Override
		public List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request) {
			this.request = request;
			return markers;
		}
	}

	private static class CapturingRegionMarkerRepository implements RegionMarkerRepository {

		private final List<RegionMarkerResponse> markers;
		private RegionMarkersRequest request;

		CapturingRegionMarkerRepository(List<RegionMarkerResponse> markers) {
			this.markers = markers;
		}

		@Override
		public List<RegionMarkerResponse> findRegionMarkers(RegionMarkersRequest request) {
			this.request = request;
			return markers;
		}
	}
}
