package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.application.ingest.normalization.RtmsJibunPnuNormalizer;

final class RtmsPnuBuilder {

	private RtmsPnuBuilder() {
	}

	static Optional<String> build(OpenApiTradeItem item) {
		return Optional.ofNullable(RtmsJibunPnuNormalizer.normalize(item).derivedPnu());
	}
}
