package com.home.infrastructure.web.read;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.PropertyReadUseCase;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restDocs")
@WebMvcTest({
	SearchController.class,
	RegionController.class,
	DetailController.class
})
@AutoConfigureRestDocs
@ActiveProfiles("test")
class ReadApiRestDocsTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PropertyReadUseCase readUseCase;

	@Test
	@DisplayName("GET /api/v1/search/complexes REST Docs를 생성한다")
	void documentSearchComplexes() throws Exception {
		given(readUseCase.searchComplexes(eq("Sample")))
			.willReturn(List.of(new SearchComplexResult(
				501L,
				"Sample Apartment",
				1001L,
				37.5123,
				127.0456,
				"Sample address"
			)));

		mockMvc.perform(get("/api/v1/search/complexes").param("q", "Sample"))
			.andExpect(status().isOk())
			.andDo(document("read-search-complexes-success",
				queryParameters(
					parameterWithName("q").description("Trimmed complex search query.")
				),
				responseFields(
					fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
					fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
					fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id used by map/detail APIs."),
					fieldWithPath("[].latitude").type(JsonFieldType.NUMBER).description("Complex marker latitude."),
					fieldWithPath("[].longitude").type(JsonFieldType.NUMBER).description("Complex marker longitude."),
					fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Parcel address.")
				),
				resource(builder()
					.tag("Read")
					.summary("Search complexes")
					.description("Searches apartment complexes by user-entered text.")
					.queryParameters(parameterWithName("q").description("Search query."))
					.responseFields(
						fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
						fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
						fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("[].latitude").type(JsonFieldType.NUMBER).description("Latitude."),
						fieldWithPath("[].longitude").type(JsonFieldType.NUMBER).description("Longitude."),
						fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Address.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/search/complexes/suggestions REST Docs를 생성한다")
	void documentComplexSuggestions() throws Exception {
		given(readUseCase.suggestComplexes(eq("Sample")))
			.willReturn(List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address")));

		mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", "Sample"))
			.andExpect(status().isOk())
			.andDo(document("read-complex-suggestions-success",
				queryParameters(
					parameterWithName("q").description("Trimmed complex suggestion query.")
				),
				responseFields(
					fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
					fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
					fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
					fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Parcel address.")
				),
				resource(builder()
					.tag("Read")
					.summary("Suggest complexes")
					.description("Returns lightweight complex suggestions for autocomplete.")
					.queryParameters(parameterWithName("q").description("Suggestion query."))
					.responseFields(
						fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
						fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
						fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Address.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/region과 GET /api/v1/region/{regionId} REST Docs를 생성한다")
	void documentRegionNavigation() throws Exception {
		given(readUseCase.getRootRegions())
			.willReturn(List.of(new RegionSummaryResult(1L, "Seoul")));
		given(readUseCase.getRegionDetail(1L))
			.willReturn(new RegionDetailResult(
				1L,
				"Seoul",
				37.5663,
				126.9780,
				List.of(new RegionSummaryResult(11L, "Gangnam-gu"))
			));

		mockMvc.perform(get("/api/v1/region"))
			.andExpect(status().isOk())
			.andDo(document("read-root-regions-success",
				responseFields(
					fieldWithPath("[].id").type(JsonFieldType.NUMBER).description("Root region id."),
					fieldWithPath("[].name").type(JsonFieldType.STRING).description("Root region name.")
				),
				resource(builder()
					.tag("Read")
					.summary("Get root regions")
					.description("Returns root regions for region navigation.")
					.responseFields(
						fieldWithPath("[].id").type(JsonFieldType.NUMBER).description("Root region id."),
						fieldWithPath("[].name").type(JsonFieldType.STRING).description("Root region name.")
					)
					.build())
			));

		mockMvc.perform(get("/api/v1/region/{regionId}", 1L))
			.andExpect(status().isOk())
			.andDo(document("read-region-detail-success",
				pathParameters(
					parameterWithName("regionId").description("Region id.")
				),
				responseFields(
					fieldWithPath("id").type(JsonFieldType.NUMBER).description("Region id."),
					fieldWithPath("name").type(JsonFieldType.STRING).description("Region name."),
					fieldWithPath("latitude").type(JsonFieldType.NUMBER).optional().description("Region center latitude."),
					fieldWithPath("longitude").type(JsonFieldType.NUMBER).optional().description("Region center longitude."),
					fieldWithPath("children").type(JsonFieldType.ARRAY).description("Child regions."),
					fieldWithPath("children[].id").type(JsonFieldType.NUMBER).description("Child region id."),
					fieldWithPath("children[].name").type(JsonFieldType.STRING).description("Child region name.")
				),
				resource(builder()
					.tag("Read")
					.summary("Get region detail")
					.description("Returns one region, its children, and center coordinates.")
					.pathParameters(parameterWithName("regionId").description("Region id."))
					.responseFields(
						fieldWithPath("id").type(JsonFieldType.NUMBER).description("Region id."),
						fieldWithPath("name").type(JsonFieldType.STRING).description("Region name."),
						fieldWithPath("latitude").type(JsonFieldType.NUMBER).optional().description("Latitude."),
						fieldWithPath("longitude").type(JsonFieldType.NUMBER).optional().description("Longitude."),
						fieldWithPath("children").type(JsonFieldType.ARRAY).description("Child regions."),
						fieldWithPath("children[].id").type(JsonFieldType.NUMBER).description("Child region id."),
						fieldWithPath("children[].name").type(JsonFieldType.STRING).description("Child region name.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/region/{regionId}/complexes REST Docs를 생성한다")
	void documentRegionComplexes() throws Exception {
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

		mockMvc.perform(get("/api/v1/region/{regionId}/complexes", 11L)
				.param("limit", "25")
				.param("offset", "50"))
			.andExpect(status().isOk())
			.andDo(document("read-region-complexes-success",
				pathParameters(
					parameterWithName("regionId").description("Region id.")
				),
				queryParameters(
					parameterWithName("limit").optional().description("Optional page size."),
					parameterWithName("offset").optional().description("Optional zero-based row offset.")
				),
				responseFields(complexSummaryFields()),
				resource(builder()
					.tag("Read")
					.summary("Get region complexes")
					.description("Returns a paged list of complexes under one region and its children.")
					.pathParameters(parameterWithName("regionId").description("Region id."))
					.queryParameters(
						parameterWithName("limit").optional().description("Optional page size."),
						parameterWithName("offset").optional().description("Optional zero-based row offset.")
					)
					.responseFields(complexSummaryFields())
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/detail/{parcelId}와 GET /api/v1/trade/{parcelId} REST Docs를 생성한다")
	void documentDetailAndTrade() throws Exception {
		given(readUseCase.getParcelDetail(1001L, 501L))
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
		given(readUseCase.getTradeList(1001L, 501L))
			.willReturn(new TradeListResult(1001L, 501L, List.of(
				new TradeResult(9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15)
			)));

		mockMvc.perform(get("/api/v1/detail/{parcelId}", 1001L).param("complexId", "501"))
			.andExpect(status().isOk())
			.andDo(document("read-detail-success",
				pathParameters(
					parameterWithName("parcelId").description("Parcel id.")
				),
				queryParameters(
					parameterWithName("complexId").optional().description("Optional selected complex id.")
				),
				responseFields(
					fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
					fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected or representative complex id."),
					fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Detail display latitude."),
					fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Detail display longitude."),
					fieldWithPath("address").type(JsonFieldType.STRING).optional().description("Parcel address."),
					fieldWithPath("tradeName").type(JsonFieldType.STRING).optional().description("Representative trade name."),
					fieldWithPath("name").type(JsonFieldType.STRING).description("Representative complex name."),
					fieldWithPath("dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
					fieldWithPath("unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
					fieldWithPath("platArea").type(JsonFieldType.NUMBER).optional().description("Plat area."),
					fieldWithPath("archArea").type(JsonFieldType.NUMBER).optional().description("Architecture area."),
					fieldWithPath("totArea").type(JsonFieldType.NUMBER).optional().description("Total area."),
					fieldWithPath("bcRat").type(JsonFieldType.NUMBER).optional().description("Building coverage ratio."),
					fieldWithPath("vlRat").type(JsonFieldType.NUMBER).optional().description("Floor area ratio."),
					fieldWithPath("useDate").type(JsonFieldType.STRING).optional().description("Use approval date.")
				),
				resource(builder()
					.tag("Read")
					.summary("Get parcel detail")
					.description("Returns parcel and selected or representative complex details.")
					.pathParameters(parameterWithName("parcelId").description("Parcel id."))
					.queryParameters(parameterWithName("complexId").optional().description("Optional selected complex id."))
					.responseFields(
						fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected or representative complex id."),
						fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Detail display latitude."),
						fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Detail display longitude."),
						fieldWithPath("address").type(JsonFieldType.STRING).optional().description("Parcel address."),
						fieldWithPath("tradeName").type(JsonFieldType.STRING).optional().description("Representative trade name."),
						fieldWithPath("name").type(JsonFieldType.STRING).description("Representative complex name."),
						fieldWithPath("dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
						fieldWithPath("unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
						fieldWithPath("platArea").type(JsonFieldType.NUMBER).optional().description("Plat area."),
						fieldWithPath("archArea").type(JsonFieldType.NUMBER).optional().description("Architecture area."),
						fieldWithPath("totArea").type(JsonFieldType.NUMBER).optional().description("Total area."),
						fieldWithPath("bcRat").type(JsonFieldType.NUMBER).optional().description("Building coverage ratio."),
						fieldWithPath("vlRat").type(JsonFieldType.NUMBER).optional().description("Floor area ratio."),
						fieldWithPath("useDate").type(JsonFieldType.STRING).optional().description("Use approval date.")
					)
					.build())
			));

		mockMvc.perform(get("/api/v1/trade/{parcelId}", 1001L).param("complexId", "501"))
			.andExpect(status().isOk())
			.andDo(document("read-trade-success",
				pathParameters(
					parameterWithName("parcelId").description("Parcel id.")
				),
				queryParameters(
					parameterWithName("complexId").optional().description("Optional selected complex id.")
				),
				responseFields(
					fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
					fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected complex id when scoped."),
					fieldWithPath("trades").type(JsonFieldType.ARRAY).description("Trades under the parcel complexes."),
					fieldWithPath("trades[].tradeId").type(JsonFieldType.NUMBER).description("Trade id."),
					fieldWithPath("trades[].dealDate").type(JsonFieldType.STRING).description("Deal date."),
					fieldWithPath("trades[].exclArea").type(JsonFieldType.NUMBER).optional().description("Exclusive area."),
					fieldWithPath("trades[].dealAmount").type(JsonFieldType.NUMBER).description("Deal amount in 10,000 KRW units."),
					fieldWithPath("trades[].aptDong").type(JsonFieldType.STRING).optional().description("Apartment dong."),
					fieldWithPath("trades[].floor").type(JsonFieldType.NUMBER).optional().description("Floor.")
				),
				resource(builder()
					.tag("Read")
					.summary("Get parcel trades")
					.description("Returns trades newest first for selected complex or complexes under a parcel.")
					.pathParameters(parameterWithName("parcelId").description("Parcel id."))
					.queryParameters(parameterWithName("complexId").optional().description("Optional selected complex id."))
					.responseFields(
						fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected complex id when scoped."),
						fieldWithPath("trades").type(JsonFieldType.ARRAY).description("Trades under the parcel complexes."),
						fieldWithPath("trades[].tradeId").type(JsonFieldType.NUMBER).description("Trade id."),
						fieldWithPath("trades[].dealDate").type(JsonFieldType.STRING).description("Deal date."),
						fieldWithPath("trades[].exclArea").type(JsonFieldType.NUMBER).optional().description("Exclusive area."),
						fieldWithPath("trades[].dealAmount").type(JsonFieldType.NUMBER).description("Deal amount in 10,000 KRW units."),
						fieldWithPath("trades[].aptDong").type(JsonFieldType.STRING).optional().description("Apartment dong."),
						fieldWithPath("trades[].floor").type(JsonFieldType.NUMBER).optional().description("Floor.")
					)
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/detail/{parcelId}/complexes REST Docs를 생성한다")
	void documentParcelComplexes() throws Exception {
		given(readUseCase.getParcelComplexes(1001L))
			.willReturn(List.of(new ComplexSummaryResult(
				501L,
				"Tower A",
				1001L,
				37.5123,
				127.0456,
				"Sample address",
				5,
				320,
				LocalDate.of(2015, 3, 20)
			)));

		mockMvc.perform(get("/api/v1/detail/{parcelId}/complexes", 1001L))
			.andExpect(status().isOk())
			.andDo(document("read-parcel-complexes-success",
				pathParameters(
					parameterWithName("parcelId").description("Parcel id.")
				),
				responseFields(complexSummaryFields()),
				resource(builder()
					.tag("Read")
					.summary("Get parcel complexes")
					.description("Returns selectable complexes under one parcel.")
					.pathParameters(parameterWithName("parcelId").description("Parcel id."))
					.responseFields(complexSummaryFields())
					.build())
			));
	}

	@Test
	@DisplayName("GET /api/v1/complex/{complexId}와 GET /api/v1/complex/{complexId}/trades REST Docs를 생성한다")
	void documentComplexDetailAndTrades() throws Exception {
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
		given(readUseCase.getComplexTradeList(502L))
			.willReturn(new TradeListResult(1001L, 502L, List.of(
				new TradeResult(9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9)
			)));

		mockMvc.perform(get("/api/v1/complex/{complexId}", 502L))
			.andExpect(status().isOk())
			.andDo(document("read-complex-detail-success",
				pathParameters(
					parameterWithName("complexId").description("Complex id.")
				),
				responseFields(detailFields()),
				resource(builder()
					.tag("Read")
					.summary("Get complex detail")
					.description("Returns detail for one complex id.")
					.pathParameters(parameterWithName("complexId").description("Complex id."))
					.responseFields(detailFields())
					.build())
			));

		mockMvc.perform(get("/api/v1/complex/{complexId}/trades", 502L))
			.andExpect(status().isOk())
			.andDo(document("read-complex-trades-success",
				pathParameters(
					parameterWithName("complexId").description("Complex id.")
				),
				responseFields(tradeListFields()),
				resource(builder()
					.tag("Read")
					.summary("Get complex trades")
					.description("Returns active trades newest first for one complex id.")
					.pathParameters(parameterWithName("complexId").description("Complex id."))
					.responseFields(tradeListFields())
					.build())
			));
	}

	private static org.springframework.restdocs.payload.FieldDescriptor[] complexSummaryFields() {
		return new org.springframework.restdocs.payload.FieldDescriptor[] {
			fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
			fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
			fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
			fieldWithPath("[].latitude").type(JsonFieldType.NUMBER).optional().description("Display latitude."),
			fieldWithPath("[].longitude").type(JsonFieldType.NUMBER).optional().description("Display longitude."),
			fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Parcel address."),
			fieldWithPath("[].dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
			fieldWithPath("[].unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
			fieldWithPath("[].useDate").type(JsonFieldType.STRING).optional().description("Use approval date.")
		};
	}

	private static org.springframework.restdocs.payload.FieldDescriptor[] detailFields() {
		return new org.springframework.restdocs.payload.FieldDescriptor[] {
			fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
			fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected or representative complex id."),
			fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Detail display latitude."),
			fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Detail display longitude."),
			fieldWithPath("address").type(JsonFieldType.STRING).optional().description("Parcel address."),
			fieldWithPath("tradeName").type(JsonFieldType.STRING).optional().description("Representative trade name."),
			fieldWithPath("name").type(JsonFieldType.STRING).description("Representative complex name."),
			fieldWithPath("dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
			fieldWithPath("unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
			fieldWithPath("platArea").type(JsonFieldType.NUMBER).optional().description("Plat area."),
			fieldWithPath("archArea").type(JsonFieldType.NUMBER).optional().description("Architecture area."),
			fieldWithPath("totArea").type(JsonFieldType.NUMBER).optional().description("Total area."),
			fieldWithPath("bcRat").type(JsonFieldType.NUMBER).optional().description("Building coverage ratio."),
			fieldWithPath("vlRat").type(JsonFieldType.NUMBER).optional().description("Floor area ratio."),
			fieldWithPath("useDate").type(JsonFieldType.STRING).optional().description("Use approval date.")
		};
	}

	private static org.springframework.restdocs.payload.FieldDescriptor[] tradeListFields() {
		return new org.springframework.restdocs.payload.FieldDescriptor[] {
			fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
			fieldWithPath("complexId").type(JsonFieldType.NUMBER).optional().description("Selected complex id when scoped."),
			fieldWithPath("trades").type(JsonFieldType.ARRAY).description("Trades under the parcel complexes."),
			fieldWithPath("trades[].tradeId").type(JsonFieldType.NUMBER).description("Trade id."),
			fieldWithPath("trades[].dealDate").type(JsonFieldType.STRING).description("Deal date."),
			fieldWithPath("trades[].exclArea").type(JsonFieldType.NUMBER).optional().description("Exclusive area."),
			fieldWithPath("trades[].dealAmount").type(JsonFieldType.NUMBER).description("Deal amount in 10,000 KRW units."),
			fieldWithPath("trades[].aptDong").type(JsonFieldType.STRING).optional().description("Apartment dong."),
			fieldWithPath("trades[].floor").type(JsonFieldType.NUMBER).optional().description("Floor.")
		};
	}
}
