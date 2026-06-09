package com.home.application.ingest.trade;

import java.util.List;

import com.home.domain.ingest.source.IngestSource;

public record OpenApiTradeIngestBatch(
	String source,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	List<OpenApiTradeItem> items
) {

	public OpenApiTradeIngestBatch {
		source = IngestSource.ofOrDefault(source, "RTMS").value();
		if (!hasText(lawdCd)) {
			throw new IllegalArgumentException("lawdCd is required");
		}
		if (!hasText(dealYmd)) {
			throw new IllegalArgumentException("dealYmd is required");
		}
		lawdCd = lawdCd.trim();
		dealYmd = dealYmd.trim();
		pageNo = pageNo == null ? 1 : pageNo;
		items = items == null ? List.of() : List.copyOf(items);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
