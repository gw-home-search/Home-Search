package com.home.user.config;

import com.home.application.auth.RefreshTokenSettings;
import com.home.application.auth.port.OpaqueTokenGenerator;
import com.home.application.auth.port.TokenClock;
import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.CookieProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class UserServiceBeans {
    @Bean
    OpaqueTokenGenerator opaqueTokenGenerator() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[48];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        };
    }

    @Bean
    TokenClock tokenClock(Clock clock) {
        return clock::instant;
    }

    @Bean
    RefreshTokenSettings refreshTokenSettings(AuthProperties properties) {
        return new RefreshTokenSettings(properties.refreshTtl());
    }

    @Bean
    Clock userClock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultCookieSerializer oauthSessionCookieSerializer(CookieProperties properties) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("OAUTH_SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.secure());
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        return serializer;
    }
}
