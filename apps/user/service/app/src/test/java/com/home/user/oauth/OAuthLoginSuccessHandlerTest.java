package com.home.user.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.auth.RefreshTokenService;
import com.home.user.cookie.RefreshTokenCookieFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
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
                new RefreshTokenCookieFactory(true, Duration.ofDays(30), "prod"),
                sessions,
                "https://home.example/auth/success");

        assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, authentication))
                .isInstanceOf(IllegalStateException.class);
        verify(sessions).invalidate(request);
    }
}
