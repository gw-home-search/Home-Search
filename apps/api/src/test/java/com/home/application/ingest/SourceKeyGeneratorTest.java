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
		return new OpenApiTradeItem(
			aptDong,
			"Sample Apartment",
			aptSeq,
			dealAmount,
			1,
			12,
			2025,
			exclArea,
			12,
			jibun,
			"11680",
			"10300",
			"{}"
		);
	}
}
