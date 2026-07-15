package com.home.infrastructure.external.complex;

record BuildingMetadataEndpointPaths(String title, String recap, boolean usesLegacy) {
    static BuildingMetadataEndpointPaths resolve(
            String canonicalTitle, String canonicalRecap, String legacyBldTitle, String legacyRecapTitle) {
        String title = text(canonicalTitle);
        String recap = text(canonicalRecap);
        boolean legacy = title == null || recap == null;
        if (title == null) title = required(legacyRecapTitle, "legacy recap-title-path");
        if (recap == null) recap = required(legacyBldTitle, "legacy bld-title-path");
        return new BuildingMetadataEndpointPaths(title, recap, legacy);
    }

    private static String required(String value, String name) {
        String text = text(value);
        if (text == null) throw new IllegalArgumentException(name + " is required when canonical path is absent");
        return text;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
