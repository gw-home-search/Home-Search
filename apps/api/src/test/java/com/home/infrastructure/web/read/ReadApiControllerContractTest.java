package com.home.infrastructure.web.read;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.application.read.PropertyReadUseCase;
import com.home.global.error.ResourceNotFoundException;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
	SearchController.class,
	RegionController.class,
	DetailController.class
})
@ActiveProfiles("test")
class ReadApiControllerContractTest {

	private static final String OFFSET_TIMESTAMP_PATTERN =
		"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})$";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PropertyReadUseCase readUseCase;

	@Test
	@DisplayName("GET /api/v1/search/complexes는 canonical search field를 반환한다")
	void searchComplexesReturnsCanonicalFields() throws Exception {
		given(readUseCase.searchComplexes(eq("Sample")))
			.willReturn(List.of(new SearchComplexResponse(
				501L,
				"Sample Apartment",
				1001L,
				37.5123,
				127.0456,
				"Sample address"
			)));

		mockMvc.perform(get("/api/v1/search/complexes").param("q", "  Sample  "))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].complexId").value(501))
			.andExpect(jsonPath("$[0].complexName").value("Sample Apartment"))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].latitude").value(37.5123))
			.andExpect(jsonPath("$[0].longitude").value(127.0456))
			.andExpect(jsonPath("$[0].address").value("Sample address"))
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/search/complexes는 blank search에서 empty array를 반환한다")
	void blankSearchReturnsEmptyArray() throws Exception {
		given(readUseCase.searchComplexes(eq("")))
			.willReturn(List.of());

		mockMvc.perform(get("/api/v1/search/complexes").param("q", " "))
			.andExpect(status().isOk())
			.andExpect(content().json("[]"));
	}

	@Test
	@DisplayName("GET /api/v1/region은 root region을 반환한다")
	void rootRegionsReturnCanonicalFields() throws Exception {
		given(readUseCase.getRootRegions())
			.willReturn(List.of(new RegionSummaryResponse(1L, "Seoul")));

		mockMvc.perform(get("/api/v1/region"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].name").value("Seoul"));
	}

	@Test
	@DisplayName("GET /api/v1/region/{regionId}는 region detail과 child region을 반환한다")
	void regionDetailReturnsChildrenAndCenter() throws Exception {
		given(readUseCase.getRegionDetail(1L))
			.willReturn(new RegionDetailResponse(
				1L,
				"Seoul",
				37.5663,
				126.9780,
				List.of(new RegionSummaryResponse(11L, "Gangnam-gu"))
			));

		mockMvc.perform(get("/api/v1/region/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.name").value("Seoul"))
			.andExpect(jsonPath("$.latitude").value(37.5663))
			.andExpect(jsonPath("$.longitude").value(126.9780))
			.andExpect(jsonPath("$.children[0].id").value(11))
			.andExpect(jsonPath("$.children[0].name").value("Gangnam-gu"));
	}

	@Test
	@DisplayName("GET /api/v1/detail/{parcelId}는 parcel과 representative complex detail을 반환한다")
	void parcelDetailReturnsCanonicalFields() throws Exception {
		given(readUseCase.getParcelDetail(eq(1001L), isNull()))
			.willReturn(new ParcelDetailResponse(
				1001L,
				501L,
				37.5123,
				127.0456,
				"Sample address",
				"Sample trade name",
				"Sample Apartment",
				8,
				740,
				new BigDecimal("12345.67"),
				new BigDecimal("2345.67"),
				new BigDecimal("98765.43"),
				new BigDecimal("22.50"),
				new BigDecimal("199.80"),
				LocalDate.of(2015, 3, 20)
			));

		mockMvc.perform(get("/api/v1/detail/1001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(501))
			.andExpect(jsonPath("$.latitude").value(37.5123))
			.andExpect(jsonPath("$.longitude").value(127.0456))
			.andExpect(jsonPath("$.address").value("Sample address"))
			.andExpect(jsonPath("$.tradeName").value("Sample trade name"))
			.andExpect(jsonPath("$.name").value("Sample Apartment"))
			.andExpect(jsonPath("$.dongCnt").value(8))
			.andExpect(jsonPath("$.unitCnt").value(740))
			.andExpect(jsonPath("$.platArea").value(12345.67))
			.andExpect(jsonPath("$.archArea").value(2345.67))
			.andExpect(jsonPath("$.totArea").value(98765.43))
			.andExpect(jsonPath("$.bcRat").value(22.50))
			.andExpect(jsonPath("$.vlRat").value(199.80))
			.andExpect(jsonPath("$.useDate").value("2015-03-20"))
			.andExpect(jsonPath("$.complexPk").doesNotExist())
			.andExpect(jsonPath("$.aptSeq").doesNotExist())
			.andExpect(jsonPath("$.sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/detail/{parcelId}?complexId= 는 선택한 complex detail을 반환한다")
	void complexScopedParcelDetailReturnsSelectedComplex() throws Exception {
		given(readUseCase.getParcelDetail(1001L, 502L))
			.willReturn(new ParcelDetailResponse(
				1001L,
				502L,
				37.6123,
				127.1456,
				"Sample address",
				"Tower B",
				"Sample Tower B",
				5,
				320,
				null,
				null,
				null,
				null,
				null,
				LocalDate.of(2020, 1, 1)
			));

		mockMvc.perform(get("/api/v1/detail/1001").param("complexId", "502"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(502))
			.andExpect(jsonPath("$.latitude").value(37.6123))
			.andExpect(jsonPath("$.longitude").value(127.1456))
			.andExpect(jsonPath("$.name").value("Sample Tower B"))
			.andExpect(jsonPath("$.unitCnt").value(320));
	}

	@Test
	@DisplayName("GET /api/v1/trade/{parcelId}는 trade를 newest first로 반환한다")
	void tradeListReturnsCanonicalFields() throws Exception {
		given(readUseCase.getTradeList(eq(1001L), isNull()))
			.willReturn(new TradeListResponse(1001L, null, List.of(
				new TradeResponse(9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15),
				new TradeResponse(9001L, LocalDate.of(2025, 12, 1), new BigDecimal("84.93"), 125000L, "101", 12)
			)));

		mockMvc.perform(get("/api/v1/trade/1001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").isEmpty())
			.andExpect(jsonPath("$.trades[0].tradeId").value(9002))
			.andExpect(jsonPath("$.trades[0].dealDate").value("2025-12-15"))
			.andExpect(jsonPath("$.trades[0].exclArea").value(84.93))
			.andExpect(jsonPath("$.trades[0].dealAmount").value(130000))
			.andExpect(jsonPath("$.trades[0].aptDong").value("101"))
			.andExpect(jsonPath("$.trades[0].floor").value(15))
			.andExpect(jsonPath("$.trades[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$.trades[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$.trades[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/trade/{parcelId}?complexId= 는 선택한 complex trade만 반환한다")
	void complexScopedTradeListReturnsSelectedComplexTrades() throws Exception {
		given(readUseCase.getTradeList(1001L, 502L))
			.willReturn(new TradeListResponse(1001L, 502L, List.of(
				new TradeResponse(9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9)
			)));

		mockMvc.perform(get("/api/v1/trade/1001").param("complexId", "502"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(502))
			.andExpect(jsonPath("$.trades[0].tradeId").value(9101))
			.andExpect(jsonPath("$.trades[0].aptDong").value("201"));
	}

	@Test
	@DisplayName("GET read endpoint는 missing parent에서 ProblemDetail 404를 반환한다")
	void missingReadResourceReturnsProblemDetail404() throws Exception {
		given(readUseCase.getRegionDetail(404L))
			.willThrow(new ResourceNotFoundException("region not found"));

		mockMvc.perform(get("/api/v1/region/404"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("Resource not found."))
			.andExpect(jsonPath("$.exception").value("ResourceNotFoundException"))
			.andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
	}
}
