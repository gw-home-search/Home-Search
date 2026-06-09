package com.home.domain.ingest.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsBatchIdentityTest {

	@Test
	@DisplayName("RTMS batch identity는 lawdCd와 dealYmd를 trim한다")
	void trimsRtmsBatchIdentity() {
		assertThat(RtmsLawdCode.of(" 11680 ").value()).isEqualTo("11680");
		assertThat(RtmsDealMonth.of(" 202512 ").value()).isEqualTo("202512");
	}

	@Test
	@DisplayName("RTMS batch identity는 잘못된 lawdCd와 dealYmd를 거부한다")
	void rejectsInvalidRtmsBatchIdentity() {
		assertThatThrownBy(() -> RtmsLawdCode.of("1168A"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("lawdCd must be a 5 digit RTMS code");

		assertThatThrownBy(() -> RtmsDealMonth.of("202513"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("dealYmd must be yyyyMM");
	}

	@Test
	@DisplayName("RTMS batch identity는 optional 조회 조건을 null로 정규화한다")
	void normalizesOptionalCriteria() {
		assertThat(RtmsLawdCode.optional(" ").map(RtmsLawdCode::value)).isEmpty();
		assertThat(RtmsDealMonth.optional(null).map(RtmsDealMonth::value)).isEmpty();
	}
}
