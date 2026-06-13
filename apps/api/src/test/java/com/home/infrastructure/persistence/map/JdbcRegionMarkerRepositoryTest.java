package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.application.map.RegionMarkerResult;
import com.home.application.map.RegionMarkerQuery;

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
				RegionMarkerResult::id,
				RegionMarkerResult::name,
				RegionMarkerResult::lat,
				RegionMarkerResult::lng,
				RegionMarkerResult::trend,
				RegionMarkerResult::unitCntSum
			)
			.containsExactly(tuple(11L, "Gangnam-gu", 37.5172, 127.0473, null, 1300L));
	}

	@Test
	@DisplayName("bounds query는 다른 region level과 marker coordinate가 없는 region을 제외한다")
	void boundsQueryExcludesOtherLevelsAndNullCoordinates() {
		seedRegionMarkers();
		JdbcRegionMarkerRepository repository = new JdbcRegionMarkerRepository(jdbcClient);

		var markers = repository.findRegionMarkers(request("eup-myeon-dong"));

		assertThat(markers)
			.extracting(RegionMarkerResult::id, RegionMarkerResult::name, RegionMarkerResult::unitCntSum)
			.containsExactly(tuple(111L, "Cheongdam-dong", 1250L));
	}

	@Test
	@DisplayName("bounds query는 하위 complex 세대수 metadata가 없어 region 세대수 합계가 없으면 marker를 반환하지 않는다")
	void boundsQueryExcludesRegionMarkerWhenUnitCountSumIsMissing() {
		seedRegionMarkersWithMissingUnitCounts();
		JdbcRegionMarkerRepository repository = new JdbcRegionMarkerRepository(jdbcClient);

		var markers = repository.findRegionMarkers(request("si-gun-gu"));

		assertThat(markers).isEmpty();
	}

	private RegionMarkerQuery request(String region) {
		return new RegionMarkerQuery(
			37.45,
			126.85,
			37.70,
			127.20,
			region
		);
	}

	private void seedRegionMarkers() {
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng, unit_cnt_sum)
			VALUES
			    (1, NULL, '11', 'Seoul', 'si-do', 37.5663, 126.9780, 1400),
			    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473, 1300),
			    (12, 1, '11710', 'Songpa-gu', 'si-gun-gu', 37.7140, 127.1230, 100),
			    (111, 11, '11680104', 'Cheongdam-dong', 'eup-myeon-dong', 37.5194, 127.0496, 1250),
			    (112, 11, '11680105', 'Samseong-dong', 'eup-myeon-dong', NULL, 127.0630, 50)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 111, '1168010400100010001', 'Cheongdam 1', 37.5194, 127.0496),
			    (1002, 111, '1168010400100010002', 'Cheongdam 2', 37.5195, 127.0497)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (2001, 1001, 'complex-2001', 'apt-2001', 'Cheongdam A', 740),
			    (2002, 1002, 'complex-2002', 'apt-2002', 'Cheongdam B', 460)
			""").update();
	}

	private void seedRegionMarkersWithMissingUnitCounts() {
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES
			    (1, NULL, '11', 'Seoul', 'si-do', 37.5663, 126.9780),
			    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473),
			    (111, 11, '11680104', 'Cheongdam-dong', 'eup-myeon-dong', 37.5194, 127.0496)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 111, '1168010400100010001', 'Cheongdam 1', 37.5194, 127.0496)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (2001, 1001, 'complex-2001', 'apt-2001', 'Cheongdam A', NULL)
			""").update();
	}
}
