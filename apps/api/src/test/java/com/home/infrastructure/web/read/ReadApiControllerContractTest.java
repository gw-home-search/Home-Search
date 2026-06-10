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
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;

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
			.willReturn(List.of(new SearchComplexResult(
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
			.willReturn(List.of(new RegionSummaryResult(1L, "Seoul")));

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
			.willReturn(new RegionDetailResult(
				1L,
				"Seoul",
				37.5663,
				126.9780,
				List.of(new RegionSummaryResult(11L, "Gangnam-gu"))
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
			.willReturn(new ParcelDetailResult(
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
			.willReturn(new ParcelDetailResult(
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
			.willReturn(new TradeListResult(1001L, null, List.of(
				new TradeResult(9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15),
				new TradeResult(9001L, LocalDate.of(2025, 12, 1), new BigDecimal("84.93"), 125000L, "101", 12)
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
			.willReturn(new TradeListResult(1001L, 502L, List.of(
				new TradeResult(9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9)
			)));

		mockMvc.perform(get("/api/v1/trade/1001").param("complexId", "502"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(502))
			.andExpect(jsonPath("$.trades[0].tradeId").value(9101))
			.andExpect(jsonPath("$.trades[0].aptDong").value("201"));
	}

	@Test
	@DisplayName("GET /api/v1/detail/{parcelId}/complexes는 같은 parcel의 complex 목록을 반환한다")
	void parcelComplexesReturnSelectableComplexSummaries() throws Exception {
		given(readUseCase.getParcelComplexes(1001L))
			.willReturn(List.of(
				new ComplexSummaryResult(501L, "Tower A", 1001L, 37.5123, 127.0456, "Sample address", 5, 320,
					LocalDate.of(2015, 3, 20)),
				new ComplexSummaryResult(502L, "Tower B", 1001L, 37.6123, 127.1456, "Sample address", 6, 410,
					LocalDate.of(2020, 1, 1))
			));

		mockMvc.perform(get("/api/v1/detail/1001/complexes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].complexId").value(501))
			.andExpect(jsonPath("$[0].complexName").value("Tower A"))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].latitude").value(37.5123))
			.andExpect(jsonPath("$[0].longitude").value(127.0456))
			.andExpect(jsonPath("$[0].address").value("Sample address"))
			.andExpect(jsonPath("$[0].dongCnt").value(5))
			.andExpect(jsonPath("$[0].unitCnt").value(320))
			.andExpect(jsonPath("$[0].useDate").value("2015-03-20"))
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/complex/{complexId}는 complexId 단독 detail을 반환한다")
	void complexDetailByComplexIdReturnsCanonicalDetail() throws Exception {
		given(readUseCase.getComplexDetail(502L))
			.willReturn(new ParcelDetailResult(
				1001L,
				502L,
				37.6123,
				127.1456,
				"Sample address",
				"Tower B",
				"Sample Tower B",
				6,
				410,
				null,
				null,
				null,
				null,
				null,
				LocalDate.of(2020, 1, 1)
			));

		mockMvc.perform(get("/api/v1/complex/502"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(502))
			.andExpect(jsonPath("$.latitude").value(37.6123))
			.andExpect(jsonPath("$.longitude").value(127.1456))
			.andExpect(jsonPath("$.address").value("Sample address"))
			.andExpect(jsonPath("$.tradeName").value("Tower B"))
			.andExpect(jsonPath("$.name").value("Sample Tower B"))
			.andExpect(jsonPath("$.unitCnt").value(410))
			.andExpect(jsonPath("$.complexPk").doesNotExist())
			.andExpect(jsonPath("$.aptSeq").doesNotExist())
			.andExpect(jsonPath("$.sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/complex/{complexId}/trades는 complexId 단독 trade list를 반환한다")
	void complexTradeListByComplexIdReturnsCanonicalTrades() throws Exception {
		given(readUseCase.getComplexTradeList(502L))
			.willReturn(new TradeListResult(1001L, 502L, List.of(
				new TradeResult(9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9)
			)));

		mockMvc.perform(get("/api/v1/complex/502/trades"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parcelId").value(1001))
			.andExpect(jsonPath("$.complexId").value(502))
			.andExpect(jsonPath("$.trades[0].tradeId").value(9101))
			.andExpect(jsonPath("$.trades[0].dealAmount").value(90000))
			.andExpect(jsonPath("$.trades[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$.trades[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$.trades[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/search/complexes/suggestions는 autocomplete field를 반환한다")
	void complexSuggestionsReturnAutocompleteFields() throws Exception {
		given(readUseCase.suggestComplexes("Sample"))
			.willReturn(List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address")));

		mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", "  Sample  "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].complexId").value(501))
			.andExpect(jsonPath("$[0].complexName").value("Sample Apartment"))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].address").value("Sample address"))
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/v1/search/complexes/suggestions는 blank query에서 empty array를 반환한다")
	void blankComplexSuggestionsReturnEmptyArray() throws Exception {
		given(readUseCase.suggestComplexes(""))
			.willReturn(List.of());

		mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", " "))
			.andExpect(status().isOk())
			.andExpect(content().json("[]"));
	}

	@Test
	@DisplayName("GET /api/v1/region/{regionId}/complexes는 region complex page를 반환한다")
	void regionComplexesReturnPagedComplexSummaries() throws Exception {
		given(readUseCase.getRegionComplexes(11L, 25, 50))
			.willReturn(List.of(new ComplexSummaryResult(
				701L,
				"Region Complex",
				2001L,
				37.5123,
				127.0456,
				"Region address",
				8,
				740,
				LocalDate.of(2018, 5, 1)
			)));

		mockMvc.perform(get("/api/v1/region/11/complexes")
				.param("limit", "25")
				.param("offset", "50"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].complexId").value(701))
			.andExpect(jsonPath("$[0].complexName").value("Region Complex"))
			.andExpect(jsonPath("$[0].parcelId").value(2001))
			.andExpect(jsonPath("$[0].latitude").value(37.5123))
			.andExpect(jsonPath("$[0].longitude").value(127.0456))
			.andExpect(jsonPath("$[0].address").value("Region address"))
			.andExpect(jsonPath("$[0].unitCnt").value(740));
	}

	@Test
	@DisplayName("GET /api/v1/region/{regionId}/complexes는 invalid page request를 ProblemDetail 400으로 반환한다")
	void invalidRegionComplexPageReturnsProblemDetail400() throws Exception {
		given(readUseCase.getRegionComplexes(11L, 0, 0))
			.willThrow(new InvalidReadRequestException("limit must be greater than 0"));

		mockMvc.perform(get("/api/v1/region/11/complexes")
				.param("limit", "0")
				.param("offset", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("Invalid parameter format."))
			.andExpect(jsonPath("$.exception").value("MapApiException"));
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
