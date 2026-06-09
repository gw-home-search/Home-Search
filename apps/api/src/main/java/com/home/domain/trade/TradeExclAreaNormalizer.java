package com.home.domain.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TradeExclAreaNormalizer {

	private static final int SCALE = 2;

	private TradeExclAreaNormalizer() {
	}

	public static BigDecimal normalize(Double value) {
		if (value == null) {
			return null;
		}
		return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
	}

	public static Double normalizeToDouble(Double value) {
		BigDecimal normalized = normalize(value);
		return normalized == null ? null : normalized.doubleValue();
	}
}
