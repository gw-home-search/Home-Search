package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

class JpaPropertyReadRepositoryTest extends JdbcPostgresTestSupport {

	private EntityManagerFactory entityManagerFactory;
	private EntityManager entityManager;

	@BeforeEach
	void setUpJpa() {
		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan("com.home.infrastructure.persistence.read");
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.setJpaPropertyMap(jpaProperties());
		factoryBean.afterPropertiesSet();
		entityManagerFactory = factoryBean.getObject();
		entityManager = entityManagerFactory.createEntityManager();
	}

	@AfterEach
	void tearDownJpa() {
		if (entityManager != null) {
			entityManager.close();
		}
		if (entityManagerFactory != null) {
			entityManagerFactory.close();
		}
	}

	@Test
	@DisplayName("JPA read repository는 public read API 5개를 contract shape로 조회한다")
	void jpaRepositoryReadsPublicReadApiContractShape() {
		seedPropertyExplorationData();
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.searchComplexes("sample"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Sample trade name");
				assertThat(result.parcelId()).isEqualTo(1001L);
				assertThat(result.latitude()).isEqualTo(37.5123);
				assertThat(result.longitude()).isEqualTo(127.0456);
				assertThat(result.address()).isEqualTo("Sample address");
			});
		assertThat(repository.findRootRegions())
			.extracting("id", "name")
			.containsExactly(tuple(1L, "Seoul"));
		assertThat(repository.findRegionDetail(1L))
			.hasValueSatisfying(region -> {
				assertThat(region.latitude()).isEqualTo(37.5663);
				assertThat(region.longitude()).isEqualTo(126.9780);
				assertThat(region.children())
					.extracting("id", "name")
					.containsExactly(tuple(11L, "Gangnam-gu"));
			});
		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(1001L);
				assertThat(detail.complexId()).isEqualTo(501L);
				assertThat(detail.name()).isEqualTo("Sample Apartment");
				assertThat(detail.tradeName()).isEqualTo("Sample trade name");
			});
		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId")
				.containsExactly(9002L, 9001L));
	}

	@Test
	@DisplayName("JPA read repository는 complexId scope로 detail과 trade를 같은 parcel 안에서 제한한다")
	void jpaRepositoryScopesDetailAndTradesToSelectedComplex() {
		seedTwoComplexParcel();
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.findParcelDetail(2001L, 702L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(2001L);
				assertThat(detail.complexId()).isEqualTo(702L);
				assertThat(detail.name()).isEqualTo("Complex B");
			});
		assertThat(repository.findTradeList(2001L, 702L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId")
				.containsExactly(9702L));
		assertThat(repository.findParcelDetail(2001L, 999L)).isEmpty();
		assertThat(repository.findTradeList(2001L, 999L)).isEmpty();
	}

	@Test
	@DisplayName("JPA read repository는 추가 public read API 5개를 조회한다")
	void jpaRepositoryReadsExpandedPublicReadApis() {
		seedTwoComplexParcel();
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.findParcelComplexes(2001L))
			.hasValueSatisfying(complexes -> assertThat(complexes)
				.extracting("complexId", "complexName", "parcelId", "unitCnt")
				.containsExactly(
					tuple(701L, "Complex A trade", 2001L, 210),
					tuple(702L, "Complex B trade", 2001L, 320)
				));
		assertThat(repository.findComplexDetail(702L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(2001L);
				assertThat(detail.complexId()).isEqualTo(702L);
				assertThat(detail.name()).isEqualTo("Complex B");
			});
		assertThat(repository.findComplexTradeList(702L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(2001L);
				assertThat(tradeList.complexId()).isEqualTo(702L);
				assertThat(tradeList.trades())
					.extracting("tradeId")
					.containsExactly(9702L);
			});
		assertThat(repository.suggestComplexes("complex", 5))
			.extracting("complexId", "complexName", "parcelId")
			.containsExactly(
				tuple(701L, "Complex A trade", 2001L),
				tuple(702L, "Complex B trade", 2001L)
			);
		assertThat(repository.findRegionComplexes(1L, 1, 1))
			.hasValueSatisfying(complexes -> assertThat(complexes)
				.extracting("complexId")
				.containsExactly(702L));
		assertThat(repository.findParcelComplexes(9999L)).isEmpty();
		assertThat(repository.findComplexDetail(9999L)).isEmpty();
		assertThat(repository.findComplexTradeList(9999L)).isEmpty();
		assertThat(repository.findRegionComplexes(9999L, 10, 0)).isEmpty();
	}

	@Test
	@DisplayName("JPA read repository는 좌표 대기 parcel의 null 좌표를 유지한다")
	void jpaRepositoryKeepsNullCoordinatesForCoordinatePendingParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '4128110100', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (3001, 1, '4128110100100010001', 'Coordinate pending address', NULL, NULL)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (801, 3001, 'COMPLEX-PK-801', 'APT-801', 'Coordinate Pending Complex', 'Coordinate Pending Trade', 180)
			""").update();
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.searchComplexes("pending"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.latitude()).isNull();
				assertThat(result.longitude()).isNull();
			});
		assertThat(repository.findParcelDetail(3001L, 801L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.latitude()).isNull();
				assertThat(detail.longitude()).isNull();
			});
	}

	@Test
	@DisplayName("JPA read repository는 HIGH confidence 재건축 필지에서 신축 단지를 대표로 반환한다")
	void jpaRepositoryPrefersSurvivingComplexForHighConfidenceRedevelopedParcel() {
		seedRedevelopedParcel("HIGH");
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.complexId()).isEqualTo(502L);
				assertThat(detail.name()).isEqualTo("New Tower");
				assertThat(detail.tradeName()).isEqualTo("New Tower Trade");
			});
	}

	@Test
	@DisplayName("JPA read repository는 LOW confidence 재건축 필지를 신축 대표로 승격하지 않는다")
	void jpaRepositoryDoesNotPromoteLowConfidenceRedevelopedParcel() {
		seedRedevelopedParcel("LOW");
		JpaPropertyReadRepository repository = new JpaPropertyReadRepository(entityManager);

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.complexId()).isEqualTo(501L);
				assertThat(detail.name()).isEqualTo("Old Mansion");
				assertThat(detail.tradeName()).isEqualTo("Old Trade");
			});
	}

	private Map<String, Object> jpaProperties() {
		Map<String, Object> properties = new HashMap<>();
		properties.put("hibernate.hbm2ddl.auto", "none");
		properties.put("hibernate.show_sql", "false");
		properties.put("hibernate.format_sql", "false");
		return properties;
	}

	private void seedTwoComplexParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400099', 'Two complex address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES
			    (701, 2001, 'COMPLEX-PK-701', 'APT-701', 'Complex A', 'Complex A trade', 210),
			    (702, 2001, 'COMPLEX-PK-702', 'APT-702', 'Complex B', 'Complex B trade', 320)
			""").update();
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			)
			VALUES
			    (9701, 'RTMS', 'jpa-scoped-701', '11680', '202512', 1, '{}', 'hash-jpa-scoped-701', 'NORMALIZED', now()),
			    (9702, 'RTMS', 'jpa-scoped-702', '11680', '202512', 1, '{}', 'hash-jpa-scoped-702', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES
			    (9701, 701, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'jpa-scoped-701', 'COMPLEX-PK-701', 'APT-701', 9701),
			    (9702, 702, DATE '2025-12-15', 130000, 15, 59.93, '201', 'RTMS', 'jpa-scoped-702', 'COMPLEX-PK-702', 'APT-702', 9702)
			""").update();
	}

	private void seedRedevelopedParcel(String confidence) {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400009', 'Redeveloped lot', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt, use_date)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Old Mansion', 'Old Trade', 500, DATE '1985-01-01'),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'New Tower', 'New Tower Trade', 900, DATE '2022-06-01')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (1001, '1168010300101400009', 'SKIPPED', 'REDEVELOPED', :confidence)
			""")
			.param("confidence", confidence)
			.update();
	}
}
