package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.TradeMatchStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexMatcherTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("aptSeq는 operational complex_id와 audit complex_pk로 resolve된다")
	void matchesByAptSeq() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem("APT-501", "Sample Apartment", "140-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(501L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-501");
		assertThat(result.matchPath()).isEqualTo("APTSEQ");
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(result.candidateCount()).isEqualTo(1);
		assertThat(result.derivedPnu()).isEqualTo("1168010300101400001");
	}

	@Test
	@DisplayName("aptSeq가 unique여도 RTMS derived PNU가 complex parcel PNU와 다르면 보류한다")
	void rejectsAptSeqMatchWhenDerivedPnuConflictsWithComplexParcel() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem("APT-501", "Sample Apartment", "999-1"));

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.PNU_CONFLICT);
		assertThat(result.derivedPnu()).isEqualTo("1168010300109990001");
		assertThat(result.candidateCount()).isEqualTo(1);
		assertThat(result.candidateComplexIds()).containsExactly(501L);
		assertThat(result.failureReason()).contains("APTSEQ parcel pnu conflict");
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
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(result.candidateCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("PNU 단일 후보라도 RTMS 이름이 master/alias와 다르면 NAME_CONFLICT로 보류한다")
	void rejectsSinglePnuCandidateWhenNameDoesNotMatchMasterOrAlias() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Different Apartment", "140-1"));

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.NAME_CONFLICT);
		assertThat(result.candidateCount()).isEqualTo(1);
		assertThat(result.candidateComplexIds()).containsExactly(501L);
		assertThat(result.failureReason()).contains("name conflict");
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
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(result.candidateCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("PNU candidate는 보존된 RTMS alias name으로도 좁혀진다")
	void matchesPnuCandidateByNameAlias() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Building Register Name', 'Official Trade Name', 120)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    502,
			    'RTMS_APT_NAME',
			    'RTMS Wobbly Name',
			    'rtmswobblyname',
			    'RTMS'
			)
			""").update();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "RTMS Wobbly Name", "140-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(502L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-502");
		assertThat(result.matchPath()).isEqualTo("PNU_ALIAS_NAME");
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(result.candidateCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("PNU candidate는 alias보다 master exact name을 우선한다")
	void prefersMasterExactNameOverAliasName() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Building Register Name', 'Official Trade Name', 120)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    501,
			    'RTMS_APT_NAME',
			    'Official Trade Name',
			    'officialtradename',
			    'RTMS'
			)
			""").update();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Official Trade Name", "140-1"));

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(502L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-502");
		assertThat(result.matchPath()).isEqualTo("PNU_NAME");
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
	}

	@Test
	@DisplayName("PNU candidate가 여러 개이고 이름 evidence가 없으면 임의 매칭하지 않는다")
	void returnsFailureWhenPnuCandidatesCannotBeDisambiguatedByNameOrAlias() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Other Apartment', 'Other trade name', 120)
			""").update();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Unknown Apartment", "140-1"));

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.AMBIGUOUS);
		assertThat(result.candidateCount()).isEqualTo(2);
		assertThat(result.failureReason()).contains("ambiguous pnu=1168010300101400001");
		assertThat(result.failureReason()).contains("aptName=Unknown Apartment");
	}

	@Test
	@DisplayName("unmatched RTMS trade는 explainable failure를 반환한다")
	void returnsExplainableFailureWhenNoComplexMatches() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem("APT-404", "Missing Apartment", "999-1"));

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.UNMATCHED);
		assertThat(result.derivedPnu()).isEqualTo("1168010300109990001");
		assertThat(result.failureReason()).contains("APT-404");
		assertThat(result.failureReason()).contains("1168010300109990001");
	}

	@Test
	@DisplayName("RTMS 지번이 invalid이면 PNU_UNAVAILABLE evidence result를 반환한다")
	void returnsPnuUnavailableWhenJibunCannotDerivePnu() {
		seedComplex();

		ComplexMatchResult result = matcher().match(rtmsItem(null, "Sample Apartment", "번지없음"));

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.PNU_UNAVAILABLE);
		assertThat(result.derivedPnu()).isNull();
		assertThat(result.failureReason()).contains("invalid jibun");
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
