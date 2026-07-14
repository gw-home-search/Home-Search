package com.home.domain.complex.buildingmetadata;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ComplexNameNormalizer {
    private static final Pattern SEARCH_SEPARATOR = Pattern.compile("[\\p{P}\\s]+");

    private ComplexNameNormalizer() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return SEARCH_SEPARATOR.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
