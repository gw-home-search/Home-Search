package com.home.application.ingest;

public interface NormalizedTradeRepository {

	boolean existsBySourceAndSourceKey(String source, String sourceKey);

	boolean insertIfAbsent(NormalizedTradeCommand command);
}
