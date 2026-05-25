package com.home.infrastructure.persistence.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.HomeSearchApiApplication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.flyway.locations", () -> "classpath:db/migration/api,classpath:db/seed/local");
		registry.add("spring.flyway.clean-disabled", () -> "true");
	}

	@Test
	@DisplayName("local runtime seed는 marker/detail/trade/duplicate/failed-match smoke를 지원한다")
	void localRuntimeSeedSupportsBaselineBackendSmoke() throws Exception {
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
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].lat").value(37.5123))
			.andExpect(jsonPath("$[0].lng").value(127.0456))
			.andExpect(jsonPath("$[0].latestDealAmount").value(130000))
			.andExpect(jsonPath("$[0].unitCntSum").value(740))
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());

		mockMvc.perform(get("/api/v1/detail/1001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.name").value("Sample Apartment"))
			.andExpect(jsonPath("$.tradeName").value("Sample trade name"))
			.andExpect(jsonPath("$.complexPk").doesNotExist())
			.andExpect(jsonPath("$.sourceKey").doesNotExist());

		mockMvc.perform(get("/api/v1/trade/1001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.trades[0].tradeId").value(9002))
			.andExpect(jsonPath("$.trades[0].dealAmount").value(130000))
			.andExpect(jsonPath("$.trades[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$.trades[0].sourceKey").doesNotExist());

		assertThat(rawCount("DUPLICATE")).isEqualTo(1L);
		assertThat(rawCount("MATCH_FAILED")).isEqualTo(1L);
		assertThat(normalizedTradeCount()).isEqualTo(2L);
	}

	private Long rawCount(String status) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM raw_trade_ingest
			WHERE status = :status
			  AND failure_reason IS NOT NULL
			""")
			.param("status", status)
			.query(Long.class)
			.single();
	}

	private Long normalizedTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade")
			.query(Long.class)
			.single();
	}
}
