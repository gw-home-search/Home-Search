package com.home.user.cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.CookieProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RefreshTokenCookieFactoryTest {
    @Test
    void createsHostOnlySecureHttpOnlyLaxAuthCookie() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var cookie = new RefreshTokenCookieFactory(
                        new CookieProperties(true),
                        new AuthProperties(URI.create("https://home.example"), Duration.ofDays(30)),
                        environment)
                .active("opaque");
        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rejectsAnInsecureProductionRefreshCookie() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new RefreshTokenCookieFactory(
                        new CookieProperties(false),
                        new AuthProperties(URI.create("https://home.example"), Duration.ofDays(30)),
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("production refresh cookie must be Secure");
    }
}
