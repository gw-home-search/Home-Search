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

import com.home.application.read.MvpReadUseCase;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeResponse;

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
	private MvpReadUseCase readUseCase;

	@Test
	@DisplayName("GET /api/v1/search/complexes REST Docs를 생성한다")
	void documentSearchComplexes() throws Exception {
		given(readUseCase.searchComplexes(eq("Sample")))
			.willReturn(List.of(new SearchComplexResponse(
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
	@DisplayName("GET /api/v1/region과 GET /api/v1/region/{regionId} REST Docs를 생성한다")
	void documentRegionNavigation() throws Exception {
		given(readUseCase.getRootRegions())
			.willReturn(List.of(new RegionSummaryResponse(1L, "Seoul")));
		given(readUseCase.getRegionDetail(1L))
			.willReturn(new RegionDetailResponse(
				1L,
				"Seoul",
				37.5663,
				126.9780,
				List.of(new RegionSummaryResponse(11L, "Gangnam-gu"))
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
	@DisplayName("GET /api/v1/detail/{parcelId}와 GET /api/v1/trade/{parcelId} REST Docs를 생성한다")
	void documentDetailAndTrade() throws Exception {
		given(readUseCase.getParcelDetail(1001L))
			.willReturn(new ParcelDetailResponse(
				1001L,
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
		given(readUseCase.getTradeList(1001L))
			.willReturn(new TradeListResponse(1001L, List.of(
				new TradeResponse(9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15)
			)));

		mockMvc.perform(get("/api/v1/detail/{parcelId}", 1001L))
			.andExpect(status().isOk())
			.andDo(document("read-detail-success",
				pathParameters(
					parameterWithName("parcelId").description("Parcel id.")
				),
				responseFields(
					fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
					fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Parcel latitude."),
					fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Parcel longitude."),
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
					.description("Returns parcel and representative complex details.")
					.pathParameters(parameterWithName("parcelId").description("Parcel id."))
					.responseFields(
						fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
						fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Parcel latitude."),
						fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Parcel longitude."),
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

		mockMvc.perform(get("/api/v1/trade/{parcelId}", 1001L))
			.andExpect(status().isOk())
			.andDo(document("read-trade-success",
				pathParameters(
					parameterWithName("parcelId").description("Parcel id.")
				),
				responseFields(
					fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
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
					.description("Returns trades newest first for complexes under a parcel.")
					.pathParameters(parameterWithName("parcelId").description("Parcel id."))
					.responseFields(
						fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
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
}
