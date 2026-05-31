package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.OpenApiTradeItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PublicComplexMetadataResolverTest {

	@Test
	@DisplayName("public complex metadata resolver는 ODC + building API 응답을 합쳐 metadata를 반환한다")
	void resolvesCombinedMetadataFromOdcloudAndBuildingApis() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			"ODC-KEY",
			"/api/AptIdInfoSvc/v1/getAptInfo",
			bldBuilder.build(),
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo"
		);

		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test/api/AptIdInfoSvc/v1/getAptInfo")))
			.andExpect(request -> assertThat(request.getURI().getRawQuery())
				.contains("cond%5BADRES::LIKE%5D=Sample%20address"))
			.andExpect(queryParam("serviceKey", "ODC-KEY"))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {
				      "ADRES": "Sample address",
				      "COMPLEX_PK": "COMPLEX-PK-501",
				      "DONG_CNT": 8,
				      "PNU": "1168010300101400001",
				      "UNIT_CNT": 740,
				      "USEAPR_DT": "20150320"
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));
		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrRecapTitleInfo")))
			.andExpect(queryParam("serviceKey", "BLD-KEY"))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
				    "body": {
				      "items": {
				        "item": [
				          {
				            "mainPurpsCd": "02000",
				            "platArea": 12345.67,
				            "archArea": 2345.67,
				            "bcRat": 22.5,
				            "totArea": 98765.43,
				            "vlRat": 199.8,
				            "hhldCnt": 740
				          }
				        ]
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		Optional<ComplexMetadata> metadata = resolver.resolve(
			rtmsItem("Sample Apartment", "777-1"),
			"1168010300107770001",
			"Sample address"
		);

		assertThat(metadata).isPresent();
		assertThat(metadata.get().dongCnt()).isEqualTo(8);
		assertThat(metadata.get().unitCnt()).isEqualTo(740);
		assertThat(metadata.get().platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
		assertThat(metadata.get().archArea()).isEqualByComparingTo(new BigDecimal("2345.67"));
		assertThat(metadata.get().totArea()).isEqualByComparingTo(new BigDecimal("98765.43"));
		assertThat(metadata.get().bcRat()).isEqualByComparingTo(new BigDecimal("22.5"));
		assertThat(metadata.get().vlRat()).isEqualByComparingTo(new BigDecimal("199.8"));
		assertThat(metadata.get().useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
		odcloudServer.verify();
		bldServer.verify();
	}

	@Test
	@DisplayName("blank service key는 HTTP lookup을 건너뛰고 empty metadata를 반환한다")
	void blankServiceKeySkipsHttpLookup() {
		RestClient odcloudClient = RestClient.builder()
			.baseUrl("https://odcloud.example.test")
			.requestFactory((uri, method) -> {
				throw new AssertionError("ODC HTTP request must not be created without service key");
			})
			.build();
		RestClient bldClient = RestClient.builder()
			.baseUrl("https://apis.example.test")
			.requestFactory((uri, method) -> {
				throw new AssertionError("BLD HTTP request must not be created without service key");
			})
			.build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudClient,
			" ",
			"/api/AptIdInfoSvc/v1/getAptInfo",
			bldClient,
			" ",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo"
		);

		assertThat(resolver.resolve(rtmsItem("Sample Apartment", "777-1"), "1168010300107770001", "Sample address"))
			.isEmpty();
	}

	@Test
	@DisplayName("address가 없으면 ODC는 건너뛰고 building metadata만 반환한다")
	void returnsBuildingMetadataWhenAddressIsMissing() {
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"ODC-KEY",
			"/api/AptIdInfoSvc/v1/getAptInfo",
			bldBuilder.build(),
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo"
		);

		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrRecapTitleInfo")))
			.andExpect(queryParam("serviceKey", "BLD-KEY"))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
				    "body": {
				      "items": {
				        "item": []
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));
		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrTitleInfo")))
			.andExpect(queryParam("serviceKey", "BLD-KEY"))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
				    "body": {
				      "items": {
				        "item": [
				          {
				            "mainPurpsCd": "02000",
				            "platArea": 12345.67,
				            "archArea": 2345.67,
				            "bcRat": 22.5,
				            "totArea": 98765.43,
				            "vlRat": 199.8,
				            "hhldCnt": 740
				          }
				        ]
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		Optional<ComplexMetadata> metadata = resolver.resolve(
			rtmsItem("Sample Apartment", "777-1"),
			"1168010300107770001",
			null
		);

		assertThat(metadata).isPresent();
		assertThat(metadata.get().dongCnt()).isNull();
		assertThat(metadata.get().unitCnt()).isEqualTo(740);
		assertThat(metadata.get().platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
		bldServer.verify();
	}

	private OpenApiTradeItem rtmsItem(String aptName, String jibun) {
		return new OpenApiTradeItem(
			"101",
			aptName,
			"APT-LIVE-501",
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-LIVE-501\",\"jibun\":\"%s\"}".formatted(jibun)
		);
	}
}
