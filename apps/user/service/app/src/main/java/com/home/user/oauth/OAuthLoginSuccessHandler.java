package com.home.user.oauth;

import com.home.application.auth.RefreshTokenService;
import com.home.user.cookie.RefreshTokenCookieFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final RefreshTokenService refresh;
    private final RefreshTokenCookieFactory cookies;
    private final OAuthSessionInvalidator sessions;
    private final String redirect;

    public OAuthLoginSuccessHandler(
            RefreshTokenService refresh,
            RefreshTokenCookieFactory cookies,
            OAuthSessionInvalidator sessions,
            @Value("${home.oauth.success-redirect}") String redirect) {
        this.refresh = refresh;
        this.cookies = cookies;
        this.sessions = sessions;
        this.redirect = redirect;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        try {
            long userId = ((OAuthAuthenticatedUser) authentication.getPrincipal()).homeSearchUserId();
            var issued = refresh.issue(userId);
            response.addHeader(
                    HttpHeaders.SET_COOKIE, cookies.active(issued.rawToken()).toString());
        } finally {
            sessions.invalidate(request);
        }
        response.sendRedirect(redirect);
    }
}
