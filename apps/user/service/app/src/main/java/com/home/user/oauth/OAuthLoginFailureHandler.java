package com.home.user.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
    private final OAuthSessionInvalidator sessions;
    private final String redirect;

    public OAuthLoginFailureHandler(
            OAuthSessionInvalidator sessions, @Value("${home.oauth.failure-redirect}") String redirect) {
        this.sessions = sessions;
        this.redirect = redirect;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        sessions.invalidate(request);
        response.sendRedirect(redirect);
    }
}
