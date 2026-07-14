package com.home.infrastructure.ops.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class OpsNotificationConfiguration {

    @Bean
    OpsNotifier opsNotifier(
            @Value("${home.ops.hermes.enabled:false}") boolean enabled,
            @Value("${home.ops.hermes.url:${HERMES_SLACK_URL:}}") String url,
            @Value("${home.ops.hermes.auth-token:${HERMES_AUTH_TOKEN:}}") String authToken,
            @Value("${home.ops.hermes.channel:${HERMES_SLACK_CHANNEL:}}") String channel,
            @Value("${home.ops.hermes.connect-timeout-millis:3000}") int connectTimeoutMillis,
            @Value("${home.ops.hermes.read-timeout-millis:3000}") int readTimeoutMillis) {
        if (!enabled || url == null || url.isBlank() || channel == null || channel.isBlank()) {
            return new NoopOpsNotifier();
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);
        return new HermesOpsNotifier(
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(url.trim())
                        .build(),
                authToken == null ? "" : authToken.trim(),
                channel.trim());
    }
}
