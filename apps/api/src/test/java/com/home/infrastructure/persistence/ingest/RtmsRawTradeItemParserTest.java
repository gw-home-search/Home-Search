package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.infrastructure.persistence.ingest.raw.RtmsRawTradeItemParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsRawTradeItemParserTest {

	private final RtmsRawTradeItemParser parser = new RtmsRawTradeItemParser(new ObjectMapper());

	@Test
	@DisplayName("RTMS raw parser는 저장된 JSON payload를 OpenApiTradeItem으로 복원한다")
	void parsesStoredJsonPayload() {
		RawTradeIngestRecord raw = raw("""
			{
			  "aptDong": " 101 ",
			  "aptName": " Sample Apartment ",
			  "aptSeq": " APT-501 ",
			  "dealAmount": " 125,000 ",
			  "dealDay": "15",
			  "dealMonth": 12,
			  "dealYear": 2025,
			  "exclArea": "84.93",
			  "floor": "12",
			  "jibun": "140-1",
			  "sggCd": "11680",
			  "umdCd": "10300",
			  "cdealType": "O",
			  "cdealDay": "26.03.12",
			  "rgstDate": "25.12.20"
			}
			""");

		assertThat(parser.parse(raw))
			.hasValueSatisfying(item -> {
				assertThat(item.aptDong()).isEqualTo("101");
				assertThat(item.aptName()).isEqualTo("Sample Apartment");
				assertThat(item.aptSeq()).isEqualTo("APT-501");
				assertThat(item.dealAmount()).isEqualTo("125,000");
				assertThat(item.dealDay()).isEqualTo(15);
				assertThat(item.dealMonth()).isEqualTo(12);
				assertThat(item.dealYear()).isEqualTo(2025);
				assertThat(item.exclArea()).isEqualTo(84.93);
				assertThat(item.floor()).isEqualTo(12);
				assertThat(item.isCanceled()).isTrue();
				assertThat(item.cancelDealDay()).isEqualTo("26.03.12");
				assertThat(item.registrationDate()).isEqualTo("25.12.20");
			});
	}

	@Test
	@DisplayName("RTMS raw parser는 aptNm이 없으면 aptName 필드를 단지명으로 사용한다")
	void fallsBackToAptNameField() {
		RawTradeIngestRecord raw = raw("""
			{
			  "aptName": "Fallback Apartment",
			  "dealDay": "not-a-number",
			  "excluUseAr": "not-a-number",
			  "floor": ""
			}
			""");

		assertThat(parser.parse(raw))
			.hasValueSatisfying(item -> {
				assertThat(item.aptName()).isEqualTo("Fallback Apartment");
				assertThat(item.dealDay()).isNull();
				assertThat(item.exclArea()).isNull();
				assertThat(item.floor()).isNull();
			});
	}

	@Test
	@DisplayName("RTMS raw parser는 빈 payload나 깨진 JSON이면 empty를 반환한다")
	void returnsEmptyForBlankOrInvalidPayload() {
		assertThat(parser.parse(null)).isEmpty();
		assertThat(parser.parse(raw("   "))).isEmpty();
		assertThat(parser.parse(raw("{not-json"))).isEmpty();
	}

	private RawTradeIngestRecord raw(String payload) {
		return new RawTradeIngestRecord(
			101L,
			"RTMS",
			"source-101",
			"11680",
			"202512",
			1,
			payload,
			"hash-source-101",
			RawTradeIngestStatus.MATCH_FAILED,
			null,
			Instant.parse("2025-12-20T00:00:00Z"),
			null
		);
	}
}
