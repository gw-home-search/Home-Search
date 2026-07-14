package com.home.user.security;

import com.home.user.web.UserProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class UserAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        UserProblemDetails.write(
                response,
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "인증이 필요합니다",
                "Authentication is required.",
                "AUTHENTICATION_REQUIRED",
                "AuthenticationException");
    }
}
