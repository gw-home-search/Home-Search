package com.home.infrastructure.persistence.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class ComplexMarkerSql {

    private static final String MARKER_SHAPE_FILTER = load("complex-marker-shape-filter.sql");
    private static final String TRADE_FIRST = load("complex-marker-trade-first.sql");

    private ComplexMarkerSql() {}

    static String markerShapeFilter() {
        return MARKER_SHAPE_FILTER;
    }

    static String tradeFirst() {
        return TRADE_FIRST;
    }

    private static String load(String resourceName) {
        try (InputStream input = ComplexMarkerSql.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Map marker SQL resource is missing: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load map marker SQL resource: " + resourceName, exception);
        }
    }
}
