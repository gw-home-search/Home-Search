package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceKeyGeneratorTest {

	private final SourceKeyGenerator generator = new SourceKeyGenerator();

	@Test
	@DisplayName("source key는 source, whitespace, comma amount, decimal scale을 normalize한다")
	void sourceKeyNormalizesExternalTradeIdentityFields() {
		OpenApiTradeItem first = item("APT-501", "125,000", 84.930, " 101 ", "140-1");
		OpenApiTradeItem equivalent = item(" APT-501 ", "125000", 84.93, "101", "140-1");

		assertThat(generator.generate("rtms", first))
			.isEqualTo(generator.generate(" RTMS ", equivalent))
			.startsWith("RTMS:");
	}

	@Test
	@DisplayName("source key는 excl_area를 2자리 반올림으로 정규화한다")
	void sourceKeyRoundsExclAreaToTwoDecimals() {
		OpenApiTradeItem roundedDown = item("APT-501", "125,000", 84.931, "101", "140-1");
		OpenApiTradeItem roundedValue = item("APT-501", "125,000", 84.93, "101", "140-1");

		assertThat(generator.generate("RTMS", roundedDown))
			.isEqualTo(generator.generate("RTMS", roundedValue));
	}

	@Test
	@DisplayName("source key는 같은 아파트의 서로 다른 거래를 같은 중복으로 보지 않는다")
	void sourceKeySeparatesDifferentTradeEventsInSameApartment() {
		OpenApiTradeItem firstDeal = item("APT-501", "125,000", 84.93, "101", "140-1", 1, 12);
		OpenApiTradeItem otherDealSameApartment = item("APT-501", "130,000", 84.93, "101", "140-1", 2, 12);

		assertThat(generator.generate("RTMS", firstDeal))
			.isNotEqualTo(generator.generate("RTMS", otherDealSameApartment));
	}

	@Test
	@DisplayName("source key는 해제 metadata를 거래 identity에 포함하지 않는다")
	void sourceKeyIgnoresCancellationMetadata() {
		OpenApiTradeItem activeDeal = item("APT-501", "125,000", 84.93, "101", "140-1", 1, 12);
		OpenApiTradeItem canceledDeal = new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{\"cdealType\":\"O\",\"cdealDay\":\"26.03.12\",\"rgstDate\":\"26.04.09\"}",
			"O",
			"26.03.12",
			"26.04.09"
		);

		assertThat(generator.generate("RTMS", canceledDeal))
			.isEqualTo(generator.generate("RTMS", activeDeal));
	}

	@Test
	@DisplayName("payload hash는 deterministic하며 null을 empty payload로 처리한다")
	void payloadHashIsDeterministic() {
		assertThat(generator.hashPayload("{\"a\":1}")).isEqualTo(generator.hashPayload("{\"a\":1}"));
		assertThat(generator.hashPayload(null)).isEqualTo(generator.hashPayload(""));
	}

	private OpenApiTradeItem item(
		String aptSeq,
		String dealAmount,
		Double exclArea,
		String aptDong,
		String jibun
	) {
		return item(aptSeq, dealAmount, exclArea, aptDong, jibun, 1, 12);
	}

	private OpenApiTradeItem item(
		String aptSeq,
		String dealAmount,
		Double exclArea,
		String aptDong,
		String jibun,
		Integer dealDay,
		Integer floor
	) {
		return new OpenApiTradeItem(
			aptDong,
			"Sample Apartment",
			aptSeq,
			dealAmount,
			dealDay,
			12,
			2025,
			exclArea,
			floor,
			jibun,
			"11680",
			"10300",
			"{}"
		);
	}
}
