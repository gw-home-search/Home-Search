package com.home.infrastructure.persistence.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.HomeSearchApiApplication;
import com.home.application.complex.ComplexRelationUseCase;
import com.home.application.region.RegionRelationSynchronizationGateway;
import com.home.application.region.RegionUnitCntSynchronizationService;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = HomeSearchApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("runtime-smoke")
@Testcontainers
class BaselineRuntimeSmokeTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
	);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private RegionRelationSynchronizationGateway regionRelationSynchronizationGateway;

	@Autowired
	private RegionUnitCntSynchronizationService regionUnitCntSynchronizationService;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration/api")
			.schemas("public", "reference", "batch")
			.defaultSchema("public")
			.load()
			.migrate();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
		registry.add("spring.flyway.enabled", () -> "false");
		registry.add("home.region.sync.one-shot.enabled", () -> "true");
	}

	@Test
	@DisplayName("local runtime은 synthetic sample seed 없이 public read API를 시작한다")
	void localRuntimeStartsWithoutSyntheticSampleSeed() throws Exception {
		mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());

		mockMvc.perform(get("/api/v1/region"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == '서울특별시')]").exists())
			.andExpect(jsonPath("$[?(@.name == '부산광역시')]").exists());

		assertThat(syntheticSamplePublicDataCount()).isZero();
		assertThat(normalizedTradeCount()).isZero();
		assertThat(rootRegionCount()).isGreaterThanOrEqualTo(17);
		assertThat(regionRelationSynchronizationGateway).isNotNull();
		assertThat(regionUnitCntSynchronizationService).isNotNull();
		assertThat(applicationContext.containsBean("regionUnitCntSyncApplicationRunner")).isTrue();
		assertThat(applicationContext.getBeansOfType(Flyway.class)).isEmpty();
	}

	@Test
	@DisplayName("기본 ON 복구 runner와 complex relation Bean들은 실제 DB 부트에서 wiring된다")
	void recoveryRunnersAndComplexRelationBeansAreWiredWithRealDatabase() {
		assertThat(applicationContext.containsBean("rawIngestReconciliationRunner")).isTrue();
		assertThat(applicationContext.containsBean("tradePartitionMaintenanceRunner")).isTrue();
		assertThat(applicationContext.getBean(ComplexRelationUseCase.class)).isNotNull();
		assertThat(missingRegionSeedCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("clean Flyway baseline은 later-scope와 완료된 일회성 작업 테이블을 만들지 않는다")
	void cleanFlywayBaselineDoesNotCreateLaterScopeOrCompletedOneShotTables() {
		assertThat(existingRelationNames(
			"public.rtms_backfill_job",
			"public.rtms_backfill_chunk",
			"public.rtms_backfill_chunk_run",
			"public.complex_relation_case",
			"public.complex_relation_case_complex"
		)).isEmpty();
	}

	private Long missingRegionSeedCount() {
		return jdbcClient.sql("""
			SELECT count(*) FROM region
			WHERE code IN ('43770256', '41461262', '11305108')
			  AND region_type = 'eup-myeon-dong'
			""")
			.query(Long.class)
			.single();
	}

	private Long syntheticSamplePublicDataCount() {
		return jdbcClient.sql("""
			SELECT
			    (SELECT count(*) FROM region WHERE name ILIKE 'Sample%')
			  + (SELECT count(*) FROM parcel WHERE address ILIKE 'Sample%')
			  + (SELECT count(*) FROM complex WHERE name ILIKE 'Sample%' OR trade_name ILIKE 'Sample%')
			  + (SELECT count(*) FROM raw_trade_ingest WHERE source_key LIKE 'sample-rtms-%')
			""")
			.query(Long.class)
			.single();
	}

	private Long normalizedTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade")
			.query(Long.class)
			.single();
	}

	private Long rootRegionCount() {
		return jdbcClient.sql("SELECT count(*) FROM region WHERE parent_id IS NULL")
			.query(Long.class)
			.single();
	}

	private java.util.List<String> existingRelationNames(String... relationNames) {
		return jdbcClient.sql("""
			SELECT relation_name
			FROM unnest(:relationNames::text[]) AS relation_name
			WHERE to_regclass(relation_name) IS NOT NULL
			ORDER BY relation_name
			""")
			.param("relationNames", relationNames)
			.query(String.class)
			.list();
	}
}
