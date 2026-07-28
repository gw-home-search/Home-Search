package com.home.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class UserServiceHttpOriginConfigurationTest {
    @Test
    void usesForwardedHeadersForTheExternalOAuthCallbackOrigin() throws IOException {
        var properties = new YamlPropertySourceLoader()
                .load("user-service", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(properties.getProperty("spring.security.oauth2.client.registration.kakao.redirect-uri"))
                .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    }
}
