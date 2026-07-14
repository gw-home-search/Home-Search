package com.home.admin.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.admin.internal")
public record InternalAdminClientProperties(
        boolean enabled,
        @NotNull URI propertyDataBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
    @AssertTrue(message = "internal admin client configuration is invalid")
    public boolean isValid() {
        return safeBaseUri(propertyDataBaseUrl) && positive(connectTimeout) && positive(readTimeout);
    }

    private static boolean safeBaseUri(URI uri) {
        if (uri == null) return false;
        String path = uri.getPath();
        return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && (path == null || path.isEmpty() || "/".equals(path));
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
