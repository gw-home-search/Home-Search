package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataFailureKind;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.application.ingest.metadata.ComplexMetadataStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PublicComplexMetadataResolverTest {

	private static final String ODC_APT_PATH = "/api/AptIdInfoSvc/" + "v" + "1/getAptInfo";

	@Test
	@DisplayName("public complex metadata resolver는 ODC + building API 응답을 합쳐 metadata를 반환한다")
	void resolvesCombinedMetadataFromOdcloudAndBuildingApis() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			bldBuilder.build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);

		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
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
				      "PNU": "1168010300107770001",
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

		ComplexMetadataResolution resolution = resolver.resolve("1168010300107770001", "Sample address");

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.RESOLVED);
		assertThat(resolution.metadata().dongCnt()).isEqualTo(8);
		assertThat(resolution.metadata().unitCnt()).isEqualTo(740);
		assertThat(resolution.metadata().platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
		assertThat(resolution.metadata().archArea()).isEqualByComparingTo(new BigDecimal("2345.67"));
		assertThat(resolution.metadata().totArea()).isEqualByComparingTo(new BigDecimal("98765.43"));
		assertThat(resolution.metadata().bcRat()).isEqualByComparingTo(new BigDecimal("22.5"));
		assertThat(resolution.metadata().vlRat()).isEqualByComparingTo(new BigDecimal("199.8"));
		assertThat(resolution.metadata().useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
		odcloudServer.verify();
		bldServer.verify();
	}

	@Test
	@DisplayName("ODC exact PNU 후보가 2건이면 AMBIGUOUS로 남기고 metadata를 반환하지 않는다")
	void ambiguousOdcloudPnuCandidatesReturnAmbiguousResolution() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			RestClient.builder().baseUrl("https://apis.example.test").build(),
			"https://apis.example.test",
			" ",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);
		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {"PNU": "1168010300107770001", "DONG_CNT": 8, "UNIT_CNT": 740},
				    {"PNU": "1168010300107770001", "DONG_CNT": 9, "UNIT_CNT": 741}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		var resolution = resolver.resolve(new ComplexMetadataLookup(
			501L,
			"APT-LIVE-501",
			"Live Sample Apartment",
			"1168010300107770001",
			"Sample address"
		));

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(resolution.metadata()).isNull();
		assertThat(resolution.failureKind()).isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
		assertThat(resolution.failureReason()).contains("ODC PNU candidate ambiguous");
		odcloudServer.verify();
	}

	@Test
	@DisplayName("building fallback은 property가 꺼져 있으면 ODC unavailable을 대체하지 않는다")
	void buildingFallbackDisabledDoesNotCallBuildingApi() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		RestClient bldClient = RestClient.builder()
			.baseUrl("https://apis.example.test")
			.requestFactory((uri, method) -> {
				throw new AssertionError("BLD HTTP request must not be created when fallback is disabled");
			})
			.build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			null,
			"ODC-KEY",
			ODC_APT_PATH,
			bldClient,
			null,
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			false
		);
		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));

		var resolution = resolver.resolve(new ComplexMetadataLookup(
			501L,
			"APT-LIVE-501",
			"Live Sample Apartment",
			"1168010300107770001",
			"Sample address"
		));

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.UNAVAILABLE);
		assertThat(resolution.failureKind()).isEqualTo(ComplexMetadataFailureKind.SOURCE_MISSING);
		odcloudServer.verify();
	}

	@Test
	@DisplayName("ODC와 building resolver는 포털 Encoding service key를 이중 인코딩하지 않는다")
	void resolverDoesNotDoubleEncodePortalEncodedServiceKeys() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			"https://odcloud.example.test",
			"abc%2Fdef%2Bghi%3D",
			ODC_APT_PATH,
			bldBuilder.build(),
			"https://apis.example.test",
			"jkl%2Fmno%2Bpqr%3D",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);
		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andExpect(request -> assertThat(request.getURI().getRawQuery())
				.contains("serviceKey=abc%2Fdef%2Bghi%3D")
				.doesNotContain("serviceKey=abc%252F"))
			.andRespond(withSuccess("""
				{"data": [{"PNU": "1168010300107770001", "DONG_CNT": 8, "UNIT_CNT": 740, "USEAPR_DT": "20150320"}]}
				""", MediaType.APPLICATION_JSON));
		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrRecapTitleInfo")))
			.andExpect(request -> assertThat(request.getURI().getRawQuery())
				.contains("serviceKey=jkl%2Fmno%2Bpqr%3D")
				.doesNotContain("serviceKey=jkl%252F"))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "body": {"items": {"item": [{"mainPurpsCd": "02000", "hhldCnt": 740}]}}
				  }
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(resolver.resolve(new ComplexMetadataLookup(
			501L,
			"APT-LIVE-501",
			"Live Sample Apartment",
			"1168010300107770001",
			"Sample address"
		)).status()).isEqualTo(ComplexMetadataStatus.RESOLVED);
		odcloudServer.verify();
		bldServer.verify();
	}

	@Test
	@DisplayName("building fallback 공동주택 후보가 2건이면 AMBIGUOUS로 남긴다")
	void ambiguousBuildingApartmentCandidatesReturnAmbiguousResolution() {
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"https://odcloud.example.test",
			" ",
			ODC_APT_PATH,
			bldBuilder.build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);
		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrRecapTitleInfo")))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "body": {
				      "items": {
				        "item": [
				          {"mainPurpsCd": "02000", "hhldCnt": 740},
				          {"mainPurpsCd": "02000", "hhldCnt": 741}
				        ]
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		var resolution = resolver.resolve(new ComplexMetadataLookup(
			501L,
			"APT-LIVE-501",
			"Live Sample Apartment",
			"1168010300107770001",
			null
		));

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(resolution.failureKind()).isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
		assertThat(resolution.failureReason()).contains("building apartment candidate ambiguous");
		bldServer.verify();
	}

	@Test
	@DisplayName("ODC 단일 metadata가 있으면 building 후보가 애매해도 ODC metadata를 보존한다")
	void preservesOdcloudMetadataWhenBuildingSupplementIsAmbiguous() {
		RestClient.Builder odcloudBuilder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer odcloudServer = MockRestServiceServer.bindTo(odcloudBuilder).build();
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudBuilder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			bldBuilder.build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);

		odcloudServer.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {
				      "PNU": "1168010300107770001",
				      "DONG_CNT": 8,
				      "UNIT_CNT": 740,
				      "USEAPR_DT": "20150320"
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));
		bldServer.expect(requestTo(startsWith("https://apis.example.test/1613000/BldRgstHubService/getBrRecapTitleInfo")))
			.andRespond(withSuccess("""
				{
				  "response": {
				    "body": {
				      "items": {
				        "item": [
				          {"mainPurpsCd": "02000", "hhldCnt": 740},
				          {"mainPurpsCd": "02000", "hhldCnt": 741}
				        ]
				      }
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		var resolution = resolver.resolve(new ComplexMetadataLookup(
			501L,
			"APT-LIVE-501",
			"Live Sample Apartment",
			"1168010300107770001",
			"Sample address"
		));

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.RESOLVED);
		assertThat(resolution.failureKind()).isNull();
		assertThat(resolution.metadata().dongCnt()).isEqualTo(8);
		assertThat(resolution.metadata().unitCnt()).isEqualTo(740);
		assertThat(resolution.metadata().useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
		odcloudServer.verify();
		bldServer.verify();
	}

	@Test
	@DisplayName("blank service key는 HTTP lookup을 건너뛰고 input insufficient unavailable을 반환한다")
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
			ODC_APT_PATH,
			bldClient,
			" ",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo"
		);

		ComplexMetadataResolution resolution = resolver.resolve("1168010300107770001", "Sample address");

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.UNAVAILABLE);
		assertThat(resolution.failureKind()).isEqualTo(ComplexMetadataFailureKind.INPUT_INSUFFICIENT);
	}

	@Test
	@DisplayName("metadata resolver는 ODC key나 enabled building key가 없으면 미설정 상태로 보고된다")
	void resolverReportsNotConfiguredWithoutUsableExternalKey() {
		PublicComplexMetadataResolver notConfigured = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"https://odcloud.example.test",
			" ",
			ODC_APT_PATH,
			RestClient.builder().baseUrl("https://apis.example.test").build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			false
		);
		PublicComplexMetadataResolver odcloudConfigured = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			RestClient.builder().baseUrl("https://apis.example.test").build(),
			"https://apis.example.test",
			" ",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			false
		);
		PublicComplexMetadataResolver buildingConfigured = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"https://odcloud.example.test",
			" ",
			ODC_APT_PATH,
			RestClient.builder().baseUrl("https://apis.example.test").build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);

		assertThat(notConfigured.isConfigured()).isFalse();
		assertThat(odcloudConfigured.isConfigured()).isTrue();
		assertThat(buildingConfigured.isConfigured()).isTrue();
	}

	@Test
	@DisplayName("address가 없으면 ODC는 건너뛰고 building metadata PARTIAL을 반환한다")
	void returnsPartialBuildingMetadataWhenAddressIsMissing() {
		RestClient.Builder bldBuilder = RestClient.builder().baseUrl("https://apis.example.test");
		MockRestServiceServer bldServer = MockRestServiceServer.bindTo(bldBuilder).build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			RestClient.builder().baseUrl("https://odcloud.example.test").build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			bldBuilder.build(),
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
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

		ComplexMetadataResolution resolution = resolver.resolve("1168010300107770001", null);

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.PARTIAL);
		assertThat(resolution.failureKind()).isNull();
		assertThat(resolution.metadata().dongCnt()).isNull();
		assertThat(resolution.metadata().unitCnt()).isEqualTo(740);
		assertThat(resolution.metadata().platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
		bldServer.verify();
	}

	@Test
	@DisplayName("ODC RestClientException은 transient failed로 구조화한다")
	void odcloudRestClientExceptionReturnsTransientFailedResolution() {
		RestClient odcloudClient = RestClient.builder()
			.baseUrl("https://odcloud.example.test")
			.requestFactory((uri, method) -> {
				throw new org.springframework.web.client.ResourceAccessException("timeout serviceKey=secret-value");
			})
			.build();
		RestClient bldClient = RestClient.builder()
			.baseUrl("https://apis.example.test")
			.requestFactory((uri, method) -> {
				throw new AssertionError("BLD fallback must not hide an ODC transient failure");
			})
			.build();
		PublicComplexMetadataResolver resolver = new PublicComplexMetadataResolver(
			odcloudClient,
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			bldClient,
			"https://apis.example.test",
			"BLD-KEY",
			"/1613000/BldRgstHubService/getBrRecapTitleInfo",
			"/1613000/BldRgstHubService/getBrTitleInfo",
			true
		);

		ComplexMetadataResolution resolution = resolver.resolve("1168010300107770001", "Sample address");

		assertThat(resolution.status()).isEqualTo(ComplexMetadataStatus.FAILED);
		assertThat(resolution.failureKind()).isEqualTo(ComplexMetadataFailureKind.TRANSIENT);
		assertThat(resolution.failureReason()).contains("serviceKey=[REDACTED]");
	}
}
