package com.home.infrastructure.external.vworld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.Optional;

import com.home.application.coordinate.lookup.ParcelCoordinate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VworldParcelCoordinateResolverTest {

	@Test
	@DisplayName("VWorld WFS bbox response는 live API call 없이 parcel coordinate를 resolve한다")
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

		Optional<ParcelCoordinate> coordinate = resolver.resolve(" 1168010300107770001 ");

		assertThat(coordinate).isPresent();
		assertThat(coordinate.get().latitude()).isEqualByComparingTo(new BigDecimal("37.1000"));
		assertThat(coordinate.get().longitude()).isEqualByComparingTo(new BigDecimal("127.1000"));
		server.verify();
	}

	@Test
	@DisplayName("blank VWorld service key는 HTTP lookup을 건너뛰고 empty coordinate를 반환한다")
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

		assertThat(resolver.resolve("1168010300107770001")).isEmpty();
	}

	@Test
	@DisplayName("VWorld resolver는 포털 Encoding service key를 이중 인코딩하지 않는다")
	void vworldResolverDoesNotDoubleEncodePortalEncodedServiceKey() {
		VworldParcelCoordinateProperties properties = new VworldParcelCoordinateProperties(
			"https://api.example.test",
			"/vworld/wfs",
			"abc%2Fdef%2Bghi%3D",
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
			.andExpect(request -> assertThat(request.getURI().getRawQuery())
				.contains("key=abc%2Fdef%2Bghi%3D")
				.doesNotContain("key=abc%252F"))
			.andRespond(withSuccess(jsonPayload(), MediaType.APPLICATION_JSON));

		assertThat(resolver.resolve("1168010300107770001")).isPresent();

		server.verify();
	}

	@Test
	@DisplayName("missing 또는 mismatched VWorld feature는 empty coordinate를 반환한다")
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

		assertThat(resolver.resolve("1168010300107770001")).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("동일 PNU의 여러 VWorld feature는 union bbox 중심 좌표로 resolve한다")
	void multipleMatchingFeaturesResolveToUnionBboxCenter() {
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

		Optional<ParcelCoordinate> coordinate = resolver.resolve("1168010300107770001");

		assertThat(coordinate).isPresent();
		assertThat(coordinate.get().latitude()).isEqualByComparingTo(new BigDecimal("37.2000"));
		assertThat(coordinate.get().longitude()).isEqualByComparingTo(new BigDecimal("127.2000"));
		server.verify();
	}

	@Test
	@DisplayName("VWorld HTTP failure는 ingest가 queryable match failure를 유지하도록 empty coordinate를 반환한다")
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

		assertThat(resolver.resolve("1168010300107770001")).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("blank PNU는 HTTP lookup 전에 empty coordinate를 반환한다")
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

		assertThat(resolver.resolve(" ")).isEmpty();
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

}
