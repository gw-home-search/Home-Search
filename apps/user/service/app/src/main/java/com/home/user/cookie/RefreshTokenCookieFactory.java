package com.home.user.cookie;

import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.CookieProperties;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieFactory {
    private final boolean secure;
    private final Duration ttl;

    public RefreshTokenCookieFactory(
            CookieProperties cookieProperties, AuthProperties authProperties, Environment environment) {
        if (!cookieProperties.secure() && environment.acceptsProfiles(Profiles.of("prod")))
            throw new IllegalStateException("production refresh cookie must be Secure");
        this.secure = cookieProperties.secure();
        this.ttl = authProperties.refreshTtl();
    }

    public ResponseCookie active(String value) {
        return base(value).maxAge(ttl).build();
    }

    public ResponseCookie expired() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from("refresh_token", value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/auth");
    }
}
