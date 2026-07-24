package com.home.domain.ingest.source;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;

public record RtmsSourceDate(String rawValue, LocalDate value, RtmsSourceDateQuality quality) {

    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendLiteral('.')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('.')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    public static RtmsSourceDate parse(String sourceValue) {
        String normalized = sourceValue == null || sourceValue.isBlank() ? null : sourceValue.trim();
        if (normalized == null) {
            return new RtmsSourceDate(null, null, RtmsSourceDateQuality.MISSING);
        }
        try {
            return new RtmsSourceDate(normalized, LocalDate.parse(normalized, FORMATTER), RtmsSourceDateQuality.VALID);
        } catch (DateTimeException exception) {
            return new RtmsSourceDate(normalized, null, RtmsSourceDateQuality.INVALID);
        }
    }

    public RtmsSourceDate {
        if (quality == null) {
            throw new IllegalArgumentException("quality is required");
        }
        if ((quality == RtmsSourceDateQuality.VALID) != (value != null)) {
            throw new IllegalArgumentException("valid source date must have a parsed value");
        }
        if (quality == RtmsSourceDateQuality.MISSING && rawValue != null) {
            throw new IllegalArgumentException("missing source date must not have a raw value");
        }
        if (quality == RtmsSourceDateQuality.INVALID && rawValue == null) {
            throw new IllegalArgumentException("invalid source date must retain its raw value");
        }
    }
}
