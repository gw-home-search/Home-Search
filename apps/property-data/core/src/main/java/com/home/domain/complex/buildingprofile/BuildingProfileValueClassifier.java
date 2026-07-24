package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class BuildingProfileValueClassifier {
    public BuildingProfileTypedValue classify(BuildingProfileField field, boolean present, String rawValue) {
        if (!present) return BuildingProfileTypedValue.state(BuildingProfileValueState.ABSENT, null);
        if (rawValue == null) return BuildingProfileTypedValue.state(BuildingProfileValueState.NULL, null);
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) return BuildingProfileTypedValue.state(BuildingProfileValueState.BLANK, rawValue);
        try {
            return switch (field.valueType()) {
                case TEXT ->
                    new BuildingProfileTypedValue(
                            BuildingProfileValueState.VALID, rawValue, normalized, null, null, null, null);
                case DECIMAL -> decimal(rawValue, normalized);
                case INTEGER -> integer(rawValue, normalized);
                case DATE ->
                    new BuildingProfileTypedValue(
                            BuildingProfileValueState.VALID,
                            rawValue,
                            null,
                            null,
                            null,
                            LocalDate.parse(normalized.replaceAll("[-./]", ""), DateTimeFormatter.BASIC_ISO_DATE),
                            null);
                case BOOLEAN -> bool(rawValue, normalized);
            };
        } catch (RuntimeException exception) {
            return BuildingProfileTypedValue.state(BuildingProfileValueState.INVALID, rawValue);
        }
    }

    private BuildingProfileTypedValue decimal(String raw, String value) {
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.signum() < 0) return BuildingProfileTypedValue.state(BuildingProfileValueState.INVALID, raw);
        return new BuildingProfileTypedValue(
                decimal.signum() == 0 ? BuildingProfileValueState.ZERO : BuildingProfileValueState.POSITIVE,
                raw,
                null,
                decimal,
                null,
                null,
                null);
    }

    private BuildingProfileTypedValue integer(String raw, String value) {
        long integer = new BigDecimal(value).longValueExact();
        if (integer < 0) return BuildingProfileTypedValue.state(BuildingProfileValueState.INVALID, raw);
        return new BuildingProfileTypedValue(
                integer == 0 ? BuildingProfileValueState.ZERO : BuildingProfileValueState.POSITIVE,
                raw,
                null,
                null,
                integer,
                null,
                null);
    }

    private BuildingProfileTypedValue bool(String raw, String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "1", "Y", "YES", "TRUE", "적용" ->
                new BuildingProfileTypedValue(BuildingProfileValueState.VALID, raw, null, null, null, null, true);
            case "0", "N", "NO", "FALSE", "미적용" ->
                new BuildingProfileTypedValue(BuildingProfileValueState.VALID, raw, null, null, null, null, false);
            default -> BuildingProfileTypedValue.state(BuildingProfileValueState.INVALID, raw);
        };
    }
}
