package com.home.infrastructure.ops.notification;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.ops.hermes")
public record OpsNotificationProperties(
        @DefaultValue("false") boolean enabled,
        String url,
        String authToken,
        String channel,
        @Positive @DefaultValue("3000") int connectTimeoutMillis,
        @Positive @DefaultValue("3000") int readTimeoutMillis) {

    public OpsNotificationProperties {
        url = normalize(url);
        authToken = normalize(authToken);
        channel = normalize(channel);
        if (enabled && (url.isBlank() || authToken.isBlank() || channel.isBlank())) {
            throw new IllegalArgumentException(
                    "home.ops.hermes.url, auth-token and channel are required when notifications are enabled");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
