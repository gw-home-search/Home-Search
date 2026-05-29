package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RtmsJibunPnuNormalizer;

final class RtmsPnuBuilder {

	private RtmsPnuBuilder() {
	}

	static Optional<String> build(OpenApiTradeItem item) {
		return Optional.ofNullable(RtmsJibunPnuNormalizer.normalize(item).derivedPnu());
	}
}
