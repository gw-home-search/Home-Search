package com.home.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Preserves the established unprefixed environment variable contract for external API credentials. */
@ConfigurationProperties
public record ExternalApiCredentialProperties(
        @DefaultValue("") String aptServiceKey,
        @DefaultValue("") String odcServiceKey,
        @DefaultValue("") String bldServiceKey,
        @DefaultValue("") String vwServiceKey) {

    public ExternalApiCredentialProperties {
        aptServiceKey = normalize(aptServiceKey);
        odcServiceKey = normalize(odcServiceKey);
        bldServiceKey = normalize(bldServiceKey);
        vwServiceKey = normalize(vwServiceKey);
    }

    public String aptServiceKey(String configuredValue) {
        return preferConfigured(configuredValue, aptServiceKey);
    }

    public String odcServiceKey(String configuredValue) {
        return preferConfigured(configuredValue, odcServiceKey);
    }

    public String bldServiceKey(String configuredValue) {
        return preferConfigured(configuredValue, bldServiceKey);
    }

    public String vwServiceKey(String configuredValue) {
        return preferConfigured(configuredValue, vwServiceKey);
    }

    private static String preferConfigured(String configuredValue, String environmentValue) {
        String normalized = normalize(configuredValue);
        return normalized.isEmpty() ? environmentValue : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
