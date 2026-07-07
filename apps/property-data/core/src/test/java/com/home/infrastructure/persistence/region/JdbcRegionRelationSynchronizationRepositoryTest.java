package com.home.infrastructure.persistence.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.home.application.region.RegionRelationSynchronizationResult;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRegionRelationSynchronizationRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("region hierarchy 순환이 있으면 전체 동기화를 중단한다")
	void rejectsRegionHierarchyCycle() {
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type)
			VALUES
			    (1, NULL, '11', 'Seoul', 'si-do'),
			    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu')
			""").update();
		jdbcClient.sql("UPDATE region SET parent_id = 11 WHERE id = 1").update();
		JdbcRegionRelationSynchronizationRepository repository =
			new JdbcRegionRelationSynchronizationRepository(jdbcClient, transactionTemplate);

		assertThatThrownBy(repository::synchronizeAll)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("region hierarchy cycle detected");
	}

	@Test
	@DisplayName("region 관계 동기화는 parcel과 complex 관계를 복구하고 상위 region 세대수 합계를 다시 저장한다")
	void synchronizesRelationsAndRegionUnitCountSums() {
		seedStaleRelationsAndUnitCountSums();
		JdbcRegionRelationSynchronizationRepository repository =
			new JdbcRegionRelationSynchronizationRepository(jdbcClient, transactionTemplate);

		RegionRelationSynchronizationResult result = repository.synchronizeAll();

		assertThat(result.relationChanged()).isTrue();
		assertThat(result.unitCntChanged()).isTrue();
		assertThat(result.unmatchedParcelExists()).isTrue();
		assertThat(complexRegionCodes())
			.containsEntry("matched", "11680104")
			.containsEntry("unmatched", null);
		assertThat(regionUnitCountSums())
			.containsEntry("sido", 740L)
			.containsEntry("sigungu", 740L)
			.containsEntry("emd", 740L)
			.containsEntry("empty", null);

		assertThat(repository.synchronizeAll())
			.isEqualTo(new RegionRelationSynchronizationResult(false, false, true));
	}

	private void seedStaleRelationsAndUnitCountSums() {
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, unit_cnt_sum)
			VALUES
			    (1, NULL, '11', 'Seoul', 'si-do', 9999),
			    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 9999),
			    (111, 11, '11680104', 'Cheongdam-dong', 'eup-myeon-dong', 9999),
			    (112, 11, '11680105', 'Samseong-dong', 'eup-myeon-dong', 9999)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 112, '1168010400100010001', 'Cheongdam 1', 37.5194, 127.0496),
			    (1002, 112, '9999999900100010001', 'Unknown 1', 37.5000, 127.0000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, region_id)
			VALUES
			    (2001, 1001, 'complex-2001', 'apt-2001', 'Cheongdam A', 740, 112),
			    (2002, 1002, 'complex-2002', 'apt-2002', 'Unknown A', 460, 112)
			""").update();
	}

	private Map<String, String> complexRegionCodes() {
		return jdbcClient.sql("""
			SELECT
			    max(r.code) FILTER (WHERE c.id = 2001) AS matched,
			    max(r.code) FILTER (WHERE c.id = 2002) AS unmatched
			FROM complex c
			LEFT JOIN region r ON r.id = c.region_id
			""")
			.query((resultSet, rowNumber) -> {
				Map<String, String> values = new java.util.HashMap<>();
				values.put("matched", resultSet.getString("matched"));
				values.put("unmatched", resultSet.getString("unmatched"));
				return values;
			})
			.single();
	}

	private Map<String, Long> regionUnitCountSums() {
		return jdbcClient.sql("""
			SELECT
			    max(unit_cnt_sum) FILTER (WHERE code = '11') AS sido,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680') AS sigungu,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680104') AS emd,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680105') AS empty
			FROM region
			""")
			.query((resultSet, rowNumber) -> {
				Map<String, Long> values = new java.util.HashMap<>();
				values.put("sido", resultSet.getObject("sido", Long.class));
				values.put("sigungu", resultSet.getObject("sigungu", Long.class));
				values.put("emd", resultSet.getObject("emd", Long.class));
				values.put("empty", resultSet.getObject("empty", Long.class));
				return values;
			})
			.single();
	}
}
