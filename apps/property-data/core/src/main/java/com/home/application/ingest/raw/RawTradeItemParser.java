package com.home.application.ingest.raw;

import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.Optional;

public interface RawTradeItemParser {

    Optional<OpenApiTradeItem> parse(RawTradeIngestRecord raw);

    static RawTradeItemParser noop() {
        return raw -> Optional.empty();
    }
}
