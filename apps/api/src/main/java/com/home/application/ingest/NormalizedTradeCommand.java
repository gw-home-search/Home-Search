package com.home.application.ingest;

import java.time.LocalDate;

/**
 * complex_id를 운영 relation으로 사용하고 source metadata를 audit/dedupe 용도로 보존하는 normalized trade insert command입니다.
 */
public record NormalizedTradeCommand(
	Long rawIngestId,
	Long complexId,
	LocalDate dealDate,
	Long dealAmount,
	Integer floor,
	Double exclArea,
	String aptDong,
	String source,
	String sourceKey,
	String complexPk,
	String aptSeq
) {

	public NormalizedTradeCommand {
		if (rawIngestId == null) {
			throw new IllegalArgumentException("rawIngestId is required");
		}
		if (complexId == null) {
			throw new IllegalArgumentException("complexId is required");
		}
		if (dealDate == null) {
			throw new IllegalArgumentException("dealDate is required");
		}
		if (dealAmount == null || dealAmount <= 0) {
			throw new IllegalArgumentException("dealAmount must be positive");
		}
		if (!hasText(source)) {
			throw new IllegalArgumentException("source is required");
		}
		if (!hasText(sourceKey)) {
			throw new IllegalArgumentException("sourceKey is required");
		}
		if (!hasText(complexPk)) {
			throw new IllegalArgumentException("complexPk is required");
		}
		source = source.trim();
		sourceKey = sourceKey.trim();
		complexPk = complexPk.trim();
		aptSeq = trimToNull(aptSeq);
		aptDong = trimToNull(aptDong);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}
}
