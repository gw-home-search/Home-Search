package com.home.application.ingest.raw;

import java.util.Optional;
import com.home.ingestcore.rtms.OpenApiTradeItem;

public interface RawTradeItemParser {

	Optional<OpenApiTradeItem> parse(RawTradeIngestRecord raw);

	static RawTradeItemParser noop() {
		return raw -> Optional.empty();
	}
}
