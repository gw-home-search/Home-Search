package com.home.user.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefreshTokenCookieFactoryTest {
    @Test
    void createsHostOnlySecureHttpOnlyLaxAuthCookie() {
        var cookie = new RefreshTokenCookieFactory(true, Duration.ofDays(30), "prod").active("opaque");
        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
    }
}
