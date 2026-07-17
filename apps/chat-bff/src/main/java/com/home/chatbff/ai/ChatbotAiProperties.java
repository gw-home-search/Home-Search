package com.home.chatbff.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("home.chat-bff.ai")
public record ChatbotAiProperties(URI baseUrl, Duration timeout) {
    public ChatbotAiProperties {
        if (baseUrl == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("AI base URL and positive timeout are required");
        }
    }
}
