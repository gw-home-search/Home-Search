package com.home.application.ingest;

import java.util.List;

public record OpenApiTradeIngestBatch(
	String source,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	List<OpenApiTradeItem> items
) {

	public OpenApiTradeIngestBatch {
		source = hasText(source) ? source.trim() : "RTMS";
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
