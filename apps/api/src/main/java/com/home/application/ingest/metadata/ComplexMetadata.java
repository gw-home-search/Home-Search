package com.home.application.ingest.metadata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ComplexMetadata(
	Integer dongCnt,
	Integer unitCnt,
	BigDecimal platArea,
	BigDecimal archArea,
	BigDecimal totArea,
	BigDecimal bcRat,
	BigDecimal vlRat,
	LocalDate useDate
) {

	public static ComplexMetadata empty() {
		return new ComplexMetadata(null, null, null, null, null, null, null, null);
	}

	public boolean hasAllCriticalFields() {
		return positive(dongCnt) && positive(unitCnt) && useDate != null;
	}

	private boolean positive(Integer value) {
		return value != null && value > 0;
	}
}
