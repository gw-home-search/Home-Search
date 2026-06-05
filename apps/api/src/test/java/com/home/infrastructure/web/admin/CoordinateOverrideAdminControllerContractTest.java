package com.home.infrastructure.web.admin;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.coordinate.CoordinateOverrideAdminService;
import com.home.application.coordinate.CoordinateOverrideApprovalResult;
import com.home.application.coordinate.CoordinatePendingComplex;
import com.home.application.coordinate.CoordinatePendingReason;
import com.home.application.coordinate.CoordinatePendingSummary;
import com.home.application.coordinate.InvalidCoordinateOverrideException;
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

@WebMvcTest(CoordinateOverrideAdminController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"home.admin.coordinate-override.enabled=true",
	"home.admin.coordinate-override.access-code=test-admin"
})
@Import({ WebCorsConfiguration.class, AdminCoordinateAccessConfiguration.class })
class CoordinateOverrideAdminControllerContractTest {

	private static final String ACCESS_CODE_HEADER = "X-Admin-Access-Code";
	private static final String OFFSET_TIMESTAMP_PATTERN =
		"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})$";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoordinateOverrideAdminService service;

	@Test
	@DisplayName("GET /api/v1/admin/coordinates/pending은 coordinate-pending 단지를 반환한다")
	void getPendingCoordinatesReturnsPendingComplexes() throws Exception {
		given(service.findPendingComplexes(50, 0)).willReturn(List.of(new CoordinatePendingComplex(
			1001L,
			501L,
			"1168010300101400001",
			"APT-501",
			"Pending Apartment",
			"Pending address",
			CoordinatePendingReason.PNU_COORDINATE_MISSING,
			1L,
			OffsetDateTime.parse("2026-06-03T00:00:00Z")
		)));

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].complexId").value(501))
			.andExpect(jsonPath("$[0].pnu").value("1168010300101400001"))
			.andExpect(jsonPath("$[0].aptSeq").value("APT-501"))
			.andExpect(jsonPath("$[0].aptName").value("Pending Apartment"))
			.andExpect(jsonPath("$[0].address").value("Pending address"))
			.andExpect(jsonPath("$[0].reason").value("PNU_COORDINATE_MISSING"))
			.andExpect(jsonPath("$[0].tradeCount").value(1))
			.andExpect(jsonPath("$[0].createdAt").value("2026-06-03T00:00:00Z"));
	}

	@Test
	@DisplayName("GET /api/v1/admin/coordinates/pending은 limit과 offset으로 page를 조회한다")
	void getPendingCoordinatesUsesLimitAndOffset() throws Exception {
		given(service.findPendingComplexes(25, 50)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.param("limit", "25")
				.param("offset", "50")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isOk())
			.andExpect(content().json("[]"));
	}

	@Test
	@DisplayName("GET /api/v1/admin/coordinates/pending/summary는 전체 coordinate-pending 사유 집계를 반환한다")
	void getPendingCoordinateSummaryReturnsWholePendingReasonCounts() throws Exception {
		given(service.findPendingSummary()).willReturn(new CoordinatePendingSummary(
			1429L,
			321L,
			1001L,
			107L
		));

		mockMvc.perform(get("/api/v1/admin/coordinates/pending/summary")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.totalCount").value(1429))
			.andExpect(jsonPath("$.reasonCounts.PNU_COORDINATE_MISSING").value(321))
			.andExpect(jsonPath("$.reasonCounts.SAME_PNU_MULTI_COMPLEX").value(1001))
			.andExpect(jsonPath("$.reasonCounts.COMPLEX_DISPLAY_COORDINATE_MISSING").value(107));
	}

	@Test
	@DisplayName("GET /api/v1/admin/coordinates/pending은 invalid limit을 ProblemDetail 400으로 거부한다")
	void invalidPendingLimitReturnsProblemDetail() throws Exception {
		given(service.findPendingComplexes(0, 0))
			.willThrow(new InvalidCoordinateOverrideException("limit must be greater than 0"));

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.param("limit", "0")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("GET /api/v1/admin/coordinates/pending은 invalid offset을 ProblemDetail 400으로 거부한다")
	void invalidPendingOffsetReturnsProblemDetail() throws Exception {
		given(service.findPendingComplexes(25, -1))
			.willThrow(new InvalidCoordinateOverrideException("offset must be greater than or equal to 0"));

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.param("limit", "25")
				.param("offset", "-1")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("admin coordinate API는 접근 코드가 없으면 ProblemDetail 401로 거부한다")
	void adminCoordinateApiRequiresAccessCode() throws Exception {
		mockMvc.perform(get("/api/v1/admin/coordinates/pending"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("admin coordinate API는 잘못된 접근 코드를 ProblemDetail 401로 거부한다")
	void adminCoordinateApiRejectsWrongAccessCode() throws Exception {
		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.header(ACCESS_CODE_HEADER, "wrong-admin"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("admin coordinate API는 local Vite origin CORS를 허용한다")
	void adminCoordinateApiAllowsLocalViteCorsOrigin() throws Exception {
		given(service.findPendingComplexes(50, 0)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.header(ACCESS_CODE_HEADER, "test-admin")
				.header("Origin", "http://127.0.0.1:5173"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
	}

	@Test
	@DisplayName("admin coordinate override preflight는 local Vite origin CORS를 허용한다")
	void adminCoordinateOverridePreflightAllowsLocalViteCorsOrigin() throws Exception {
		mockMvc.perform(options("/api/v1/admin/coordinates/1168010300101400001/override")
				.header("Origin", "http://127.0.0.1:5173")
				.header("Access-Control-Request-Method", "PUT")
				.header("Access-Control-Request-Headers", "content-type,x-admin-access-code"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"))
			.andExpect(header().string("Access-Control-Allow-Methods", matchesPattern(".*PUT.*")));
	}

	@Test
	@DisplayName("PUT /api/v1/admin/coordinates/{pnu}/override는 수동 좌표를 승인한다")
	void approveCoordinateOverrideReturnsApprovedResult() throws Exception {
		given(service.approve(eq("1168010300101400001"), any()))
			.willReturn(new CoordinateOverrideApprovalResult(
				"1168010300101400001",
				new BigDecimal("37.5123000"),
				new BigDecimal("127.0456000"),
				true
			));

		mockMvc.perform(put("/api/v1/admin/coordinates/1168010300101400001/override")
				.header(ACCESS_CODE_HEADER, "test-admin")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "latitude": 37.5123,
					  "longitude": 127.0456,
					  "reason": "operator verified missing coordinate",
					  "approvedBy": "test-operator"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.pnu").value("1168010300101400001"))
			.andExpect(jsonPath("$.latitude").value(37.5123))
			.andExpect(jsonPath("$.longitude").value(127.0456))
			.andExpect(jsonPath("$.parcelUpdated").value(true));
	}

	@Test
	@DisplayName("admin coordinate override는 invalid coordinate를 ProblemDetail로 거부한다")
	void invalidOverrideCoordinateReturnsProblemDetail() throws Exception {
		mockMvc.perform(put("/api/v1/admin/coordinates/1168010300101400001/override")
				.header(ACCESS_CODE_HEADER, "test-admin")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "latitude": 99,
					  "longitude": 127.0456,
					  "reason": "invalid",
					  "approvedBy": "test-operator"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("admin coordinate override는 승인 불가능한 PNU를 ProblemDetail로 거부한다")
	void nonPendingOverridePnuReturnsProblemDetail() throws Exception {
		given(service.approve(eq("1168010300101400001"), any()))
			.willThrow(new InvalidCoordinateOverrideException("not pending"));

		mockMvc.perform(put("/api/v1/admin/coordinates/1168010300101400001/override")
				.header(ACCESS_CODE_HEADER, "test-admin")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "latitude": 37.5123,
					  "longitude": 127.0456,
					  "reason": "operator verified missing coordinate",
					  "approvedBy": "test-operator"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("C401"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}

	@Test
	@DisplayName("admin coordinate API는 unexpected IllegalArgumentException을 server error로 유지한다")
	void unexpectedIllegalArgumentReturnsInternalServerError() throws Exception {
		given(service.findPendingComplexes(50, 0)).willThrow(new IllegalArgumentException("unexpected invariant"));

		mockMvc.perform(get("/api/v1/admin/coordinates/pending")
				.header(ACCESS_CODE_HEADER, "test-admin"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("S500"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}
}
