package com.home.infrastructure.ops.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsNotificationProperties.class)
public class OpsNotificationConfiguration {

    @Bean
    OpsNotifier opsNotifier(OpsNotificationProperties properties) {
        if (!properties.enabled()) {
            return new NoopOpsNotifier();
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
        requestFactory.setReadTimeout(properties.readTimeoutMillis());
        return new HermesOpsNotifier(
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(properties.url().trim())
                        .build(),
                properties.authToken().trim(),
                properties.channel().trim());
    }
}
