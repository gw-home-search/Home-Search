package com.home.application.ingest.matching;

import com.home.application.ingest.trade.OpenApiTradeItem;

@FunctionalInterface
public interface ComplexMatcher {

	ComplexMatchResult match(OpenApiTradeItem item);
}
