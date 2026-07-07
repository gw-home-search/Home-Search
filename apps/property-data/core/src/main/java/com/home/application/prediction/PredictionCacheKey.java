package com.home.application.prediction;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public record PredictionCacheKey(
	Long complexId,
	Long basisTradeId,
	YearMonth anchorMonth
) {

	private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	public PredictionCacheKey {
		Objects.requireNonNull(complexId, "complexId must not be null");
		Objects.requireNonNull(basisTradeId, "basisTradeId must not be null");
		Objects.requireNonNull(anchorMonth, "anchorMonth must not be null");
	}

	public String cacheKey() {
		return "home-search:prediction:v1:F37:complex:%d:basis:%d:ym:%s"
			.formatted(complexId, basisTradeId, anchorMonth.format(YEAR_MONTH_FORMATTER));
	}

	public String lockKey() {
		return "home-search:prediction:v1:F37:lock:complex:%d:basis:%d:ym:%s"
			.formatted(complexId, basisTradeId, anchorMonth.format(YEAR_MONTH_FORMATTER));
	}
}
