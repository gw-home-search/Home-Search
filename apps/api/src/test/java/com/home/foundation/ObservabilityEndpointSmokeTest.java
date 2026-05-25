package com.home.foundation;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.TradeIngestMetrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityEndpointSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeIngestMetrics tradeIngestMetrics;

	@Test
	@DisplayName("GET /actuator/health는 database auto-configuration 없이 readiness status를 반환한다")
	void actuatorHealthIsAvailableWithoutDatabaseAutoConfiguration() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	@DisplayName("GET /actuator/prometheus는 local scrape surface를 노출한다")
	void prometheusScrapeEndpointIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
			.andExpect(content().string(containsString("# HELP")));
	}

	@Test
	@DisplayName("GET /actuator/prometheus는 V1 RTMS ingest counter를 노출한다")
	void prometheusExposesV1IngestCounters() throws Exception {
		tradeIngestMetrics.record("RTMS", new IngestResult(3, 3, 1, 1, 1, 0));

		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("home_search_ingest_items_total")))
			.andExpect(content().string(containsString("source=\"RTMS\"")))
			.andExpect(content().string(containsString("result=\"read\"")))
			.andExpect(content().string(containsString("result=\"raw_saved\"")))
			.andExpect(content().string(containsString("result=\"normalized_inserted\"")))
			.andExpect(content().string(containsString("result=\"duplicate_skipped\"")))
			.andExpect(content().string(containsString("result=\"match_failed\"")))
			.andExpect(content().string(containsString("result=\"parse_failed\"")));
	}

	@Test
	@DisplayName("GET /actuator/prometheus는 V1 map endpoint success/error counter를 노출한다")
	void prometheusExposesV1MapEndpointCounters() throws Exception {
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
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/map/regions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "region": "invalid-level"
					}
					"""))
			.andExpect(status().isBadRequest());

		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("home_search_map_requests_total")))
			.andExpect(content().string(containsString("endpoint=\"complexes\"")))
			.andExpect(content().string(containsString("endpoint=\"regions\"")))
			.andExpect(content().string(containsString("outcome=\"success\"")))
			.andExpect(content().string(containsString("outcome=\"error\"")));
	}
}
