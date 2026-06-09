package com.home.foundation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.TradeIngestMetrics;
import com.home.application.map.MapUseCase;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityEndpointSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeIngestMetrics tradeIngestMetrics;

	@Autowired
	private MeterRegistry meterRegistry;

	@MockitoBean
	private MapUseCase mapUseCase;

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
	@DisplayName("GET /actuator/prometheus는 RTMS ingest counter를 노출한다")
	void prometheusExposesIngestCounters() throws Exception {
		tradeIngestMetrics.record("RTMS", new IngestResult(4, 4, 1, 1, 1, 1, 0));

		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("home_search_ingest_items_total")))
			.andExpect(content().string(containsString("source=\"RTMS\"")))
			.andExpect(content().string(containsString("result=\"read\"")))
			.andExpect(content().string(containsString("result=\"raw_saved\"")))
			.andExpect(content().string(containsString("result=\"normalized_inserted\"")))
			.andExpect(content().string(containsString("result=\"duplicate_skipped\"")))
			.andExpect(content().string(containsString("result=\"canceled_skipped\"")))
			.andExpect(content().string(containsString("result=\"match_failed\"")))
			.andExpect(content().string(containsString("result=\"parse_failed\"")));
	}

	@Test
	@DisplayName("GET /actuator/prometheus는 map marker cache hit miss fallback counter를 노출한다")
	void prometheusExposesMapMarkerCacheCounters() throws Exception {
		meterRegistry.counter("home.search.map.marker.cache.requests", "endpoint", "complexes", "result", "hit")
			.increment();
		meterRegistry.counter("home.search.map.marker.cache.requests", "endpoint", "complexes", "result", "miss")
			.increment();
		meterRegistry.counter("home.search.map.marker.cache.requests", "endpoint", "complexes", "result", "fallback")
			.increment();

		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("home_search_map_marker_cache_requests_total")))
			.andExpect(content().string(containsString("endpoint=\"complexes\"")))
			.andExpect(content().string(containsString("result=\"hit\"")))
			.andExpect(content().string(containsString("result=\"miss\"")))
			.andExpect(content().string(containsString("result=\"fallback\"")));
	}

	@Test
	@DisplayName("GET /actuator/prometheus는 map endpoint success/error counter를 노출한다")
	void prometheusExposesMapEndpointCounters() throws Exception {
		given(mapUseCase.getComplexMarkers(any())).willReturn(java.util.List.of());

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
