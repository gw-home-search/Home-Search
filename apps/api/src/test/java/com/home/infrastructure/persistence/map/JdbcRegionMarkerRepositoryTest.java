package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRegionMarkerRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("bounds query는 요청한 region level의 canonical region marker를 반환한다")
	void boundsQueryReturnsRegionMarkersForRequestedLevel() {
		seedRegionMarkers();
		JdbcRegionMarkerRepository repository = new JdbcRegionMarkerRepository(jdbcClient);

		var markers = repository.findRegionMarkers(request("si-gun-gu"));

		assertThat(markers)
			.extracting(
				RegionMarkerResponse::id,
				RegionMarkerResponse::name,
				RegionMarkerResponse::lat,
				RegionMarkerResponse::lng,
				RegionMarkerResponse::trend
			)
			.containsExactly(tuple(11L, "Gangnam-gu", 37.5172, 127.0473, null));
	}

	@Test
	@DisplayName("bounds query는 다른 region level과 marker coordinate가 없는 region을 제외한다")
	void boundsQueryExcludesOtherLevelsAndNullCoordinates() {
		seedRegionMarkers();
		JdbcRegionMarkerRepository repository = new JdbcRegionMarkerRepository(jdbcClient);

		var markers = repository.findRegionMarkers(request("eup-myeon-dong"));

		assertThat(markers)
			.extracting(RegionMarkerResponse::id, RegionMarkerResponse::name)
			.containsExactly(tuple(111L, "Cheongdam-dong"));
	}

	private RegionMarkersRequest request(String region) {
		return new RegionMarkersRequest(
			37.45,
			126.85,
			37.70,
			127.20,
			region
		);
	}

	private void seedRegionMarkers() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type, center_lat, center_lng)
			VALUES
			    (1, '11', 'Seoul', 'si-do', 37.5663, 126.9780),
			    (11, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473),
			    (12, '11710', 'Songpa-gu', 'si-gun-gu', 37.7140, 127.1230),
			    (111, '11680104', 'Cheongdam-dong', 'eup-myeon-dong', 37.5194, 127.0496),
			    (112, '11680105', 'Samseong-dong', 'eup-myeon-dong', NULL, 127.0630)
			""").update();
	}
}
