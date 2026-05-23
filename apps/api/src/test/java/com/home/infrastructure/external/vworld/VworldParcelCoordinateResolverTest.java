package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;
import com.home.infrastructure.persistence.ingest.ParcelCoordinate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VworldParcelCoordinateResolverTest {

	@Test
	@DisplayName("VWorld WFS bbox response resolves a parcel coordinate without a live API call")
	void resolvesParcelCoordinateFromVworldWfsBboxResponse() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"DUMMY",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);
		server.expect(requestTo(startsWith("https://api.example.test/vworld/wfs")))
			.andExpect(queryParam("key", "DUMMY"))
			.andExpect(queryParam("output", "application/json"))
			.andExpect(queryParam("pnu", "1168010300107770001"))
			.andExpect(queryParam("domain", "http://localhost:8080/test"))
			.andExpect(queryParam("srsName", "EPSG:4326"))
			.andExpect(queryParam("pageNo", "1"))
			.andExpect(queryParam("numOfRows", "100"))
			.andRespond(withSuccess(jsonPayload(), MediaType.APPLICATION_JSON));

		Optional<ParcelCoordinate> coordinate = resolver.resolve(" 1168010300107770001 ", rtmsItem());

		assertThat(coordinate).isPresent();
		assertThat(coordinate.get().latitude()).isEqualByComparingTo(new BigDecimal("37.1000"));
		assertThat(coordinate.get().longitude()).isEqualByComparingTo(new BigDecimal("127.1000"));
		server.verify();
	}

	@Test
	@DisplayName("blank VWorld service key skips HTTP lookup and returns empty coordinate")
	void blankServiceKeySkipsHttpLookupAndReturnsEmpty() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			" ",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without VW_SERVICE_KEY");
			})
			.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);

		assertThat(resolver.resolve("1168010300107770001", rtmsItem())).isEmpty();
	}

	@Test
	@DisplayName("missing or mismatched VWorld features return empty coordinate")
	void missingOrMismatchedFeaturesReturnEmpty() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"DUMMY",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);
		server.expect(requestTo(startsWith("https://api.example.test/vworld/wfs")))
			.andExpect(queryParam("key", "DUMMY"))
			.andExpect(queryParam("output", "application/json"))
			.andExpect(queryParam("pnu", "1168010300107770001"))
			.andExpect(queryParam("domain", "http://localhost:8080/test"))
			.andExpect(queryParam("srsName", "EPSG:4326"))
			.andExpect(queryParam("pageNo", "1"))
			.andExpect(queryParam("numOfRows", "100"))
			.andRespond(withSuccess("""
				{
				  "features": [
				    {"bbox": [127.0, 37.0], "properties": {"pnu": "1168010300107770001"}},
				    {"bbox": [127.0, 37.0, 127.2, 37.2], "properties": {"pnu": "9999999999999999999"}}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(resolver.resolve("1168010300107770001", rtmsItem())).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("ambiguous matching VWorld features return empty coordinate")
	void ambiguousMatchingFeaturesReturnEmpty() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"DUMMY",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);
		server.expect(requestTo(startsWith("https://api.example.test/vworld/wfs")))
			.andExpect(queryParam("pnu", "1168010300107770001"))
			.andRespond(withSuccess("""
				{
				  "features": [
				    {"bbox": [127.0, 37.0, 127.2, 37.2], "properties": {"pnu": "1168010300107770001"}},
				    {"bbox": [127.2, 37.2, 127.4, 37.4], "properties": {"pnu": "1168010300107770001"}}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(resolver.resolve("1168010300107770001", rtmsItem())).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("VWorld HTTP failure returns empty coordinate so ingest can keep a queryable match failure")
	void vworldHttpFailureReturnsEmptyCoordinate() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"DUMMY",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);
		server.expect(requestTo(startsWith("https://api.example.test/vworld/wfs")))
			.andRespond(withServerError());

		assertThat(resolver.resolve("1168010300107770001", rtmsItem())).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("blank PNU returns empty coordinate before HTTP lookup")
	void blankPnuReturnsEmptyBeforeHttpLookup() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"DUMMY",
			"http://localhost:8080/test",
			100,
			1_000,
			1_000
		);
		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("HTTP request must not be created without PNU");
			})
			.build();
		VworldParcelCoordinateResolver resolver = new VworldParcelCoordinateResolver(restClient, properties);

		assertThat(resolver.resolve(" ", rtmsItem())).isEmpty();
	}

	private String jsonPayload() {
		return """
			{
			  "features": [
			    {
			      "bbox": [127.0000, 37.0000, 127.2000, 37.2000],
			      "properties": {"pnu": "1168010300107770001"}
			    }
			  ]
			}
			""";
	}

	private OpenApiTradeItem rtmsItem() {
		return new OpenApiTradeItem(
			"101",
			"Live Sample Apartment",
			"APT-LIVE-501",
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			"777-1",
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-LIVE-501\",\"jibun\":\"777-1\"}"
		);
	}
}
