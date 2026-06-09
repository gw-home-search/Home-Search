package com.home.application.ingest.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.trade.RtmsJibunPnu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.ingest.trade.OpenApiTradeItem;

class RtmsJibunPnuNormalizerTest {

	@Test
	@DisplayName("RTMS jibun은 item sggCd와 umdCd로 display-safe PNU evidence를 만든다")
	void derivesPnuFromRtmsItemDistrictCodesAndJibun() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("11680", "10300", "140-1"));

		assertThat(pnu.available()).isTrue();
		assertThat(pnu.rawJibun()).isEqualTo("140-1");
		assertThat(pnu.normalizedJibun()).isEqualTo("140-1");
		assertThat(pnu.sggCd()).isEqualTo("11680");
		assertThat(pnu.umdCd()).isEqualTo("10300");
		assertThat(pnu.landCode()).isEqualTo("1");
		assertThat(pnu.bonbun()).isEqualTo("0140");
		assertThat(pnu.bubun()).isEqualTo("0001");
		assertThat(pnu.derivedPnu()).isEqualTo("1168010300101400001");
		assertThat(pnu.pnuUnavailableReason()).isNull();
	}

	@Test
	@DisplayName("산 지번은 land code 2와 padding된 본번/부번으로 PNU를 만든다")
	void derivesMountainLotPnu() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("11680", "10300", " 산 12-3 "));

		assertThat(pnu.available()).isTrue();
		assertThat(pnu.rawJibun()).isEqualTo("산 12-3");
		assertThat(pnu.normalizedJibun()).isEqualTo("산12-3");
		assertThat(pnu.landCode()).isEqualTo("2");
		assertThat(pnu.bonbun()).isEqualTo("0012");
		assertThat(pnu.bubun()).isEqualTo("0003");
		assertThat(pnu.derivedPnu()).isEqualTo("1168010300200120003");
	}

	@Test
	@DisplayName("부번이 없는 지번은 bubun 0000으로 PNU를 만든다")
	void derivesPnuWithoutSubLotNumber() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("11680", "10300", "140"));

		assertThat(pnu.available()).isTrue();
		assertThat(pnu.bonbun()).isEqualTo("0140");
		assertThat(pnu.bubun()).isEqualTo("0000");
		assertThat(pnu.derivedPnu()).isEqualTo("1168010300101400000");
	}

	@Test
	@DisplayName("item sggCd 또는 umdCd가 유효하지 않으면 request LAWD_CD fallback 없이 PNU_UNAVAILABLE evidence가 된다")
	void rejectsInvalidDistrictCodesWithoutRequestFallback() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("1168", "10300", "140-1"));

		assertThat(pnu.available()).isFalse();
		assertThat(pnu.derivedPnu()).isNull();
		assertThat(pnu.pnuUnavailableReason()).isEqualTo("invalid sggCd");
	}

	@Test
	@DisplayName("지번 본번이나 부번이 4자리를 넘으면 PNU_UNAVAILABLE evidence가 된다")
	void rejectsTooLongJibunParts() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("11680", "10300", "12345-1"));

		assertThat(pnu.available()).isFalse();
		assertThat(pnu.derivedPnu()).isNull();
		assertThat(pnu.pnuUnavailableReason()).isEqualTo("jibun part too long");
	}

	@Test
	@DisplayName("숫자 본번을 찾을 수 없는 지번은 PNU_UNAVAILABLE evidence가 된다")
	void rejectsJibunWithoutNumericMainLot() {
		RtmsJibunPnu pnu = RtmsJibunPnuNormalizer.normalize(item("11680", "10300", "번지없음"));

		assertThat(pnu.available()).isFalse();
		assertThat(pnu.derivedPnu()).isNull();
		assertThat(pnu.pnuUnavailableReason()).isEqualTo("invalid jibun");
	}

	private OpenApiTradeItem item(String sggCd, String umdCd, String jibun) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			jibun,
			sggCd,
			umdCd,
			"{\"jibun\":\"%s\"}".formatted(jibun)
		);
	}
}
