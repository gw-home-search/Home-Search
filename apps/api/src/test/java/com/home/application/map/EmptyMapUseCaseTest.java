package com.home.application.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

class EmptyMapUseCaseTest {

	private final EmptyMapUseCase useCase = new EmptyMapUseCase();

	@Test
	@DisplayName("placeholder map use case returns empty complex and region marker lists")
	void placeholderMapUseCaseReturnsEmptyMarkerLists() {
		assertThat(useCase.getComplexMarkers(new ComplexMarkersRequest(
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
		))).isEmpty();
		assertThat(useCase.getRegionMarkers(new RegionMarkersRequest(
			37.45,
			126.85,
			37.70,
			127.20,
			"si-gun-gu"
		))).isEmpty();
	}
}
