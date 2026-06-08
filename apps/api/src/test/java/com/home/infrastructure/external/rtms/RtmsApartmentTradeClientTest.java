package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RtmsApartmentTradeClientTest {

	private final RtmsApartmentTradeResponseParser parser = new RtmsApartmentTradeResponseParser(new ObjectMapper());

	@Test
	@DisplayName("RTMS JSON response는 live API call 없이 OpenApiTradeIngestBatch로 parse된다")
	void parseRtmsJsonResponseIntoOpenApiTradeIngestBatch() {
		String payload = """
			{
			  "response": {
			    "header": {
			      "resultCode": "000",
			      "resultMsg": "OK"
			    },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "aptDong": " 101 ",
			            "aptNm": "Sample Apartment",
			            "aptSeq": "11680-123",
			            "dealAmount": "128,500",
			            "dealDay": 15,
			            "dealMonth": 12,
			            "dealYear": 2025,
			            "excluUseAr": 84.93,
			            "floor": 12,
			            "jibun": "140-1",
			            "cdealType": "O",
			            "cdealDay": "26.03.12",
			            "rgstDate": "26.04.09",
			            "sggCd": "11680",
			            "umdCd": "10300"
			          }
			        ]
			      },
			      "numOfRows": 10,
			      "pageNo": 2,
			      "totalCount": 1
			    }
			  }
			}
			""";

		OpenApiTradeIngestBatch batch = parser.parse("11680", "202512", 2, payload);

		assertThat(batch.source()).isEqualTo("RTMS");
		assertThat(batch.lawdCd()).isEqualTo("11680");
		assertThat(batch.dealYmd()).isEqualTo("202512");
		assertThat(batch.pageNo()).isEqualTo(2);
		assertThat(batch.items()).hasSize(1);

		OpenApiTradeItem item = batch.items().get(0);
		assertThat(item.aptDong()).isEqualTo("101");
		assertThat(item.aptName()).isEqualTo("Sample Apartment");
		assertThat(item.aptSeq()).isEqualTo("11680-123");
		assertThat(item.dealAmount()).isEqualTo("128,500");
		assertThat(item.dealDay()).isEqualTo(15);
		assertThat(item.dealMonth()).isEqualTo(12);
		assertThat(item.dealYear()).isEqualTo(2025);
		assertThat(item.exclArea()).isEqualTo(84.93);
		assertThat(item.floor()).isEqualTo(12);
		assertThat(item.jibun()).isEqualTo("140-1");
		assertThat(item.cancelDealType()).isEqualTo("O");
		assertThat(item.cancelDealDay()).isEqualTo("26.03.12");
		assertThat(item.registrationDate()).isEqualTo("26.04.09");
		assertThat(item.sggCd()).isEqualTo("11680");
		assertThat(item.umdCd()).isEqualTo("10300");
		assertThat(item.payload()).contains("\"aptSeq\":\"11680-123\"");
	}

	@Test
	@DisplayName("RTMS JSON response의 page metadata는 다음 page 수집 여부를 결정한다")
	void parseRtmsJsonResponsePageMetadata() {
		String payload = """
			{
			  "response": {
			    "header": {
			      "resultCode": "000",
			      "resultMsg": "OK"
			    },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "aptNm": "Sample Apartment",
			            "aptSeq": "11680-123",
			            "dealAmount": "128,500",
			            "dealDay": 15,
			            "dealMonth": 12,
			            "dealYear": 2025,
			            "excluUseAr": 84.93,
			            "floor": 12,
			            "jibun": "140-1",
			            "sggCd": "11680",
			            "umdCd": "10300"
			          }
			        ]
			      },
			      "numOfRows": 100,
			      "pageNo": 2,
			      "totalCount": 250
			    }
			  }
			}
			""";

		RtmsApartmentTradePage page = parser.parsePage("11680", "202512", 2, payload);

		assertThat(page.batch().pageNo()).isEqualTo(2);
		assertThat(page.pageNo()).isEqualTo(2);
		assertThat(page.numOfRows()).isEqualTo(100);
		assertThat(page.totalCount()).isEqualTo(250);
		assertThat(page.hasNextPage()).isTrue();
		assertThat(page.nextRequest()).isEqualTo(new RtmsApartmentTradeRequest("11680", "202512", 3));
	}

	@Test
	@DisplayName("마지막 RTMS page metadata는 추가 fetch 없이 수집을 종료시킨다")
	void lastRtmsPageMetadataStopsPagination() {
		String payload = """
			{
			  "response": {
			    "header": {"resultCode": "000", "resultMsg": "OK"},
			    "body": {
			      "items": "",
			      "numOfRows": 100,
			      "pageNo": 3,
			      "totalCount": 250
			    }
			  }
			}
			""";

		RtmsApartmentTradePage page = parser.parsePage("11680", "202512", 3, payload);

		assertThat(page.batch().items()).isEmpty();
		assertThat(page.hasNextPage()).isFalse();
	}

	@Test
	@DisplayName("blank RTMS service key는 HTTP request 생성 전에 실패한다")
	void blankServiceKeyFailsBeforeHttpRequest() {
		RtmsApartmentTradeProperties properties = new RtmsApartmentTradeProperties(
			"https://example.invalid",
			"/rtms",
			" ",
			100,
			1_000,
			1_000
		);
		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without APT_SERVICE_KEY");
			})
			.build();
		RtmsPublicApartmentTradeClient client = new RtmsPublicApartmentTradeClient(
			restClient,
			properties,
			parser
		);

		assertThatThrownBy(() -> client.fetch(new RtmsApartmentTradeRequest("11680", "202512", 1)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APT_SERVICE_KEY");
	}

	@Test
	@DisplayName("public RTMS client는 configured endpoint를 호출하고 response를 parse한다")
	void publicClientFetchesConfiguredEndpointAndParsesResponse() {
		RtmsApartmentTradeProperties properties = new RtmsApartmentTradeProperties(
			"https://api.example.test",
			"/rtms",
			"DUMMY",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		RtmsPublicApartmentTradeClient client = new RtmsPublicApartmentTradeClient(
			restClient,
			properties,
			parser
		);
		server.expect(requestTo(
				"https://api.example.test/rtms?_type=json&serviceKey=DUMMY&LAWD_CD=11680&DEAL_YMD=202512&pageNo=4&numOfRows=100"
			))
			.andRespond(withSuccess(jsonPayload("11680-777"), MediaType.APPLICATION_JSON));

		OpenApiTradeIngestBatch batch = client.fetch(new RtmsApartmentTradeRequest("11680", "202512", 4));

		assertThat(batch.pageNo()).isEqualTo(4);
		assertThat(batch.items()).singleElement()
			.extracting(OpenApiTradeItem::aptSeq)
			.isEqualTo("11680-777");
		server.verify();
	}

	@Test
	@DisplayName("public RTMS client는 포털 Encoding service key를 이중 인코딩하지 않는다")
	void publicClientDoesNotDoubleEncodePortalEncodedServiceKey() {
		RtmsApartmentTradeProperties properties = new RtmsApartmentTradeProperties(
			"https://api.example.test",
			"/rtms",
			"abc%2Fdef%2Bghi%3D",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		RtmsPublicApartmentTradeClient client = new RtmsPublicApartmentTradeClient(
			restClient,
			properties,
			parser
		);
		server.expect(requestTo(
				"https://api.example.test/rtms?_type=json&serviceKey=abc%2Fdef%2Bghi%3D&LAWD_CD=11680&DEAL_YMD=202512&pageNo=1&numOfRows=100"
			))
			.andRespond(withSuccess(jsonPayload("11680-777"), MediaType.APPLICATION_JSON));

		client.fetch(new RtmsApartmentTradeRequest("11680", "202512", 1));

		server.verify();
	}

	@Test
	@DisplayName("textual 또는 missing RTMS item은 empty batch로 처리된다")
	void textualItemsAreEmptyBatch() {
		String payload = """
			{
			  "response": {
			    "header": {"resultCode": "000", "resultMsg": "OK"},
			    "body": {"items": "", "numOfRows": 10, "pageNo": 1, "totalCount": 0}
			  }
			}
			""";

		OpenApiTradeIngestBatch batch = parser.parse("11680", "202512", 1, payload);

		assertThat(batch.items()).isEqualTo(List.of());
	}

	private String jsonPayload(String aptSeq) {
		return """
			{
			  "response": {
			    "header": {"resultCode": "000", "resultMsg": "OK"},
			    "body": {
			      "items": {
			        "item": [
			          {
			            "aptNm": "Sample Apartment",
			            "aptSeq": "%s",
			            "dealAmount": "128,500",
			            "dealDay": 15,
			            "dealMonth": 12,
			            "dealYear": 2025,
			            "excluUseAr": 84.93,
			            "floor": 12,
			            "jibun": "140-1",
			            "sggCd": "11680",
			            "umdCd": "10300"
			          }
			        ]
			      }
			    }
			  }
			}
			""".formatted(aptSeq);
	}
}
