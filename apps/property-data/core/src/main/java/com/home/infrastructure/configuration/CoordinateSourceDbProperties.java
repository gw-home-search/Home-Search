package com.home.infrastructure.configuration;

import jakarta.validation.constraints.Positive;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.coordinate-source.db")
public record CoordinateSourceDbProperties(
        String jdbcUrl,
        String username,
        String password,
        @Positive @DefaultValue("5") int connectTimeoutSeconds,
        @Positive @DefaultValue("10") int socketTimeoutSeconds,
        @Positive @DefaultValue("1000") int lockTimeoutMillis,
        @Positive @DefaultValue("3000") int statementTimeoutMillis,
        @DefaultValue("true") boolean readOnly) {

    public CoordinateSourceDbProperties {
        jdbcUrl = normalize(jdbcUrl);
        username = normalize(username);
        password = normalize(password);
        if (!jdbcUrl.isBlank() && (username.isBlank() || password.isBlank())) {
            throw new IllegalArgumentException(
                    "home.coordinate-source.db username and password are required when jdbc-url is configured");
        }
    }

    public boolean enabled() {
        return !jdbcUrl.isBlank();
    }

    public Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
        properties.setProperty("socketTimeout", Integer.toString(socketTimeoutSeconds));
        properties.setProperty("readOnly", Boolean.toString(readOnly));
        properties.setProperty(
                "options",
                "-c lock_timeout=%d -c statement_timeout=%d".formatted(lockTimeoutMillis, statementTimeoutMillis));
        return properties;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
