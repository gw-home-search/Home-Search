package com.home.user.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.auth.RefreshTokenService;
import com.home.domain.user.OAuthProvider;
import com.home.user.config.properties.AuthProperties;
import com.home.user.config.properties.CookieProperties;
import com.home.user.config.properties.OAuthProperties;
import com.home.user.cookie.RefreshTokenCookieFactory;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;

class OAuthLoginSuccessHandlerTest {
    @Test
    void invalidatesOAuthSessionWhenRefreshIssueFails() {
        var refresh = mock(RefreshTokenService.class);
        var sessions = mock(OAuthSessionInvalidator.class);
        var authentication = mock(Authentication.class);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        when(authentication.getPrincipal()).thenReturn((OAuthAuthenticatedUser) () -> 42L);
        when(refresh.issue(42L)).thenThrow(new IllegalStateException("database unavailable"));
        var handler = new OAuthLoginSuccessHandler(
                refresh,
                new RefreshTokenCookieFactory(
                        new CookieProperties(true),
                        new AuthProperties(URI.create("https://home.example"), Duration.ofDays(30)),
                        new MockEnvironment().withProperty("spring.profiles.active", "prod")),
                sessions,
                new OAuthProperties(
                        URI.create("https://home.example/auth/success"),
                        URI.create("https://home.example/auth/failure"),
                        Set.of(OAuthProvider.KAKAO)));

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, authentication))
                .isInstanceOf(IllegalStateException.class);
        verify(sessions).invalidate(request);
    }
}
