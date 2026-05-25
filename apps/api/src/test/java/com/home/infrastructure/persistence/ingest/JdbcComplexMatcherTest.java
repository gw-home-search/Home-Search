package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.OpenApiTradeItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexMatcherTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("aptSeq는 operational complex_id와 audit complex_pk로 resolve된다")
	void matchesByAptSeq() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem("APT-501", "Sample Apartment", "999-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(501L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-501");
		assertThat(result.matchPath()).isEqualTo("APTSEQ");
	}

	@Test
	@DisplayName("RTMS jibun은 aptSeq가 없을 때 PNU를 만들고 single parcel complex를 resolve한다")
	void matchesUniqueParcelPnuFromRtmsJibun() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Sample Apartment", "140-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(501L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-501");
		assertThat(result.matchPath()).isEqualTo("PNU_UNIQUE");
	}

	@Test
	@DisplayName("PNU candidate는 RTMS apartment name으로 좁혀진다")
	void matchesPnuCandidateByName() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Other Apartment', 'Other trade name', 120)
			""").update();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Other trade name", "140-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(502L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-502");
		assertThat(result.matchPath()).isEqualTo("PNU_NAME");
	}

	@Test
	@DisplayName("unmatched RTMS trade는 explainable failure를 반환한다")
	void returnsExplainableFailureWhenNoComplexMatches() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem("APT-404", "Missing Apartment", "999-1"));

		assertThat(result.matched()).isFalse();
		assertThat(result.failureReason()).contains("APT-404");
		assertThat(result.failureReason()).contains("1168010300109990001");
	}

	private JdbcComplexMatcher matcher() {
		return new JdbcComplexMatcher(jdbcClient);
	}

	private OpenApiTradeItem rtmsItem(String aptSeq, String aptName, String jibun) {
		return new OpenApiTradeItem(
			"101",
			aptName,
			aptSeq,
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"jibun\":\"%s\"}".formatted(aptSeq, aptName, jibun)
		);
	}
}
