package com.home.infrastructure.external.odcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Set;

import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerificationStatus;
import com.home.application.coordinate.identity.ComplexCoordinateParcelTargets;
import com.home.application.coordinate.identity.ComplexCoordinateTarget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdcloudComplexCoordinateIdentityVerifierTest {

	private static final String ODC_APT_PATH = "/api/AptIdInfoSvc/" + "v" + "1/getAptInfo";

	@Test
	@DisplayName("ODC coordinate identity verifier는 aptSeq가 같은 단일 PNU면 CONFIRMED를 반환한다")
	void confirmsSingleMatchingAptSeqAndPnu() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OdcloudComplexCoordinateIdentityVerifier verifier = verifier(builder);
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

		var verification = verifier.verify(
			targets("1153011200102380000"),
			new ComplexCoordinateTarget(501L, "11530-4350", "Sample", Set.of("101"))
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.CONFIRMED);
		server.verify();
	}

	@Test
	@DisplayName("ODC coordinate identity verifier는 aptSeq 단일 후보가 parcel PNU와 다르면 AMBIGUOUS로 차단한다")
	void blocksWhenOdcloudPnuConflictsWithParcelPnu() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OdcloudComplexCoordinateIdentityVerifier verifier = verifier(builder);
		server.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {"COMPLEX_PK": "11530-4350", "PNU": "1153011200102390000"}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		var verification = verifier.verify(
			targets("1153011200102380000"),
			new ComplexCoordinateTarget(501L, "11530-4350", "Sample", Set.of("101"))
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS);
		assertThat(verification.reason()).contains("conflicts");
		server.verify();
	}

	@Test
	@DisplayName("ODC coordinate identity transient 실패는 호출 단위 retry 후 CONFIRMED를 반환한다")
	void retriesTransientOdcloudIdentityFailure() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://odcloud.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OdcloudComplexCoordinateIdentityVerifier verifier = new OdcloudComplexCoordinateIdentityVerifier(
			builder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH,
			2,
			0
		);
		server.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withServerError());
		server.expect(requestTo(startsWith("https://odcloud.example.test" + ODC_APT_PATH)))
			.andRespond(withSuccess("""
				{
				  "data": [
				    {"COMPLEX_PK": "11530-4350", "PNU": "1153011200102380000"}
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		var verification = verifier.verify(
			targets("1153011200102380000"),
			new ComplexCoordinateTarget(501L, "11530-4350", "Sample", Set.of("101"))
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.CONFIRMED);
		server.verify();
	}

	private OdcloudComplexCoordinateIdentityVerifier verifier(RestClient.Builder builder) {
		return new OdcloudComplexCoordinateIdentityVerifier(
			builder.build(),
			"https://odcloud.example.test",
			"ODC-KEY",
			ODC_APT_PATH
		);
	}

	private ComplexCoordinateParcelTargets targets(String pnu) {
		return new ComplexCoordinateParcelTargets(
			1001L,
			pnu,
			List.of(new ComplexCoordinateTarget(501L, "11530-4350", "Sample", Set.of("101")))
		);
	}
}
