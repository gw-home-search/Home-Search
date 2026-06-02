package com.home.infrastructure.external.odcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdcloudComplexIdentityResolverTest {

	private static final String ODC_APT_PATH = "/api/AptIdInfoSvc/" + "v" + "1/getAptInfo";

	@Test
	@DisplayName("ODC identity resolver는 RTMS aptSeq와 정확히 일치하는 COMPLEX_PK의 PNU를 반환한다")
	void resolvesPnuByExactComplexPk() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OdcloudComplexIdentityResolver resolver = new OdcloudComplexIdentityResolver(
			builder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH
		);
		server.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andExpect(queryParam("serviceKey", "ODC-KEY"))
			.andExpect(request -> assertThat(request.getURI().getRawQuery())
				.contains("cond%5BCOMPLEX_PK::EQ%5D=11530-4350"))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {"COMPLEX_PK": "11530-4350", "PNU": "1153011200102380000"}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		Optional<String> pnu = resolver.resolvePnu(item("11530-4350"));

		assertThat(pnu).contains("1153011200102380000");
		server.verify();
	}

	@Test
	@DisplayName("ODC identity resolver는 COMPLEX_PK 후보가 다중이면 PNU를 추측하지 않는다")
	void doesNotGuessWhenComplexPkCandidatesAreAmbiguous() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OdcloudComplexIdentityResolver resolver = new OdcloudComplexIdentityResolver(
			builder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH
		);
		server.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {"COMPLEX_PK": "11530-4350", "PNU": "1153011200102380000"},
				    {"COMPLEX_PK": "11530-4350", "PNU": "1153011200102390000"}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		assertThat(resolver.resolvePnu(item("11530-4350"))).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("ODC identity resolver는 service key가 없으면 HTTP lookup 없이 empty를 반환한다")
	void blankServiceKeySkipsHttpLookup() {
		RestClient restClient = RestClient.builder()
			.baseUrl("https://odcloud.example.test")
			.requestFactory((uri, httpMethod) -> {
				throw new AssertionError("ODC HTTP request must not be created without service key");
			})
			.build();
		OdcloudComplexIdentityResolver resolver = new OdcloudComplexIdentityResolver(
			restClient,
			"https://odcloud.example.test",
			" ",
			ODC_APT_PATH
		);

		assertThat(resolver.resolvePnu(item("11530-4350"))).isEmpty();
	}

	private OpenApiTradeItem item(String aptSeq) {
		return new OpenApiTradeItem(
			"101",
			"하버라인4단지",
			aptSeq,
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			"가-238",
			"11530",
			"11200",
			"{\"aptSeq\":\"%s\",\"jibun\":\"가-238\"}".formatted(aptSeq)
		);
	}
}
