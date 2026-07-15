package com.home.user.security;

import com.home.user.config.properties.AuthProperties;
import com.home.user.web.UserProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthOriginFilter extends OncePerRequestFilter {
    private final String allowed;

    public AuthOriginFilter(AuthProperties properties) {
        this.allowed = properties.allowedOrigin().toString();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !"POST".equals(r.getMethod())
                || !("/auth/access".equals(r.getRequestURI()) || "/auth/logout".equals(r.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain c)
            throws ServletException, IOException {
        if (!allowed.equals(r.getHeader("Origin"))) {
            UserProblemDetails.write(
                    s,
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "요청 출처가 허용되지 않았습니다",
                    "The request Origin is not allowed.",
                    "AUTH_ORIGIN_REJECTED",
                    "AuthOriginException");
            return;
        }
        c.doFilter(r, s);
    }
}
