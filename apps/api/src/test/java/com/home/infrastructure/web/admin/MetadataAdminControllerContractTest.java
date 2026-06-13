package com.home.infrastructure.web.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;
import com.home.application.ingest.metadata.admin.MetadataAdminService;
import com.home.infrastructure.web.WebCorsConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetadataAdminController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"home.admin.metadata-enrichment.enabled=true",
	"home.admin.metadata-enrichment.access-code=test-admin"
})
@Import({ WebCorsConfiguration.class, AdminMetadataAccessConfiguration.class })
class MetadataAdminControllerContractTest {
	@Autowired MockMvc mockMvc;
	@MockitoBean MetadataAdminService service;

	@Test
	@DisplayName("metadata admin API는 접근 코드로 pending과 summary를 조회한다")
	void readsPendingAndSummary() throws Exception {
		given(service.findPending(50, 0)).willReturn(List.of(new Pending(
			501L, "Sample", "APT-501", "4146126200109010000", "양지읍", "UNAVAILABLE",
			"SOURCE_MISSING", "missing", 2, OffsetDateTime.parse("2026-12-01T00:00:00Z"), null, null)));
		given(service.summary()).willReturn(new Summary(1, Map.of("UNAVAILABLE", 1L)));

		mockMvc.perform(get("/api/v1/admin/metadata/pending").header("X-Admin-Access-Code", "test-admin"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].canonicalPnu").value("4146126200109010000"));
		mockMvc.perform(get("/api/v1/admin/metadata/pending/summary").header("X-Admin-Access-Code", "test-admin"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.statusCounts.UNAVAILABLE").value(1));
		mockMvc.perform(get("/api/v1/admin/metadata/pending")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("metadata admin retry는 actor와 reason을 받아 재시도만 예약한다")
	void schedulesRetry() throws Exception {
		given(service.retry(eq(501L), anyString(), anyString())).willReturn(new ActionResult(true));
		mockMvc.perform(post("/api/v1/admin/metadata/501/retry")
				.header("X-Admin-Access-Code", "test-admin").contentType(MediaType.APPLICATION_JSON)
				.content("{\"actor\":\"operator\",\"reason\":\"source updated\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(true));
	}
}
