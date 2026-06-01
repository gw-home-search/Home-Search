package com.home.application.ingest;

import java.util.Optional;

public interface RawTradeItemParser {

	Optional<OpenApiTradeItem> parse(RawTradeIngestRecord raw);

	static RawTradeItemParser noop() {
		return raw -> Optional.empty();
	}
}
