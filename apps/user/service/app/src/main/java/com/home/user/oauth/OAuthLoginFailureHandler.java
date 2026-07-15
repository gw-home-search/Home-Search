package com.home.user.oauth;

import com.home.user.config.properties.OAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
    private final OAuthSessionInvalidator sessions;
    private final String redirect;

    public OAuthLoginFailureHandler(OAuthSessionInvalidator sessions, OAuthProperties properties) {
        this.sessions = sessions;
        this.redirect = properties.failureRedirect().toString();
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        sessions.invalidate(request);
        response.sendRedirect(redirect);
    }
}
