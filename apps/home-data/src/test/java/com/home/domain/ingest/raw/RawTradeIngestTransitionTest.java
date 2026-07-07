package com.home.domain.ingest.raw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RawTradeIngestTransitionTest {

	@Test
	@DisplayName("raw ingest transition은 저장 status와 failure reason 조합을 소유한다")
	void transitionOwnsStatusAndFailureReasonPair() {
		assertThat(RawTradeIngestTransition.normalized().status()).isEqualTo(RawTradeIngestStatus.NORMALIZED);
		assertThat(RawTradeIngestTransition.normalized().failureReason()).isNull();

		assertThat(RawTradeIngestTransition.sourceKeyDuplicate().status()).isEqualTo(RawTradeIngestStatus.DUPLICATE);
		assertThat(RawTradeIngestTransition.sourceKeyDuplicate().failureReason())
			.isEqualTo(RawTradeIngestFailureReason.SOURCE_KEY_DUPLICATE.value());

		assertThat(RawTradeIngestTransition.canceledSourceKey().status()).isEqualTo(RawTradeIngestStatus.CANCELED);
		assertThat(RawTradeIngestTransition.canceledSourceKey().failureReason())
			.isEqualTo(RawTradeIngestFailureReason.CANCELED_SOURCE_KEY.value());

		assertThat(RawTradeIngestTransition.matchFailed("no complex").status())
			.isEqualTo(RawTradeIngestStatus.MATCH_FAILED);
		assertThat(RawTradeIngestTransition.matchFailed("no complex").failureReason()).isEqualTo("no complex");
	}
}
