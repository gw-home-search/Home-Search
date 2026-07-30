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
        assertThat(properties.getProperty("home.oauth.enabled-providers"))
                .isEqualTo("${HOME_USER_OAUTH_ENABLED_PROVIDERS:google,kakao,naver}");
        assertThat(properties.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                .isEqualTo("${GOOGLE_OAUTH_CLIENT_ID:UNSET}");
        assertThat(properties.getProperty("spring.security.oauth2.client.registration.naver.client-id"))
                .isEqualTo("${NAVER_OAUTH_CLIENT_ID:UNSET}");
    }
}
