package com.home.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

final class AdminAbsoluteSessionLifetimeFilter extends OncePerRequestFilter {
    private static final String LOGIN_PATH = "/api/v1/admin/auth/login";
    private final Duration absoluteLifetime;
    private final Clock clock;

    AdminAbsoluteSessionLifetimeFilter(Duration absoluteLifetime, Clock clock) {
        if (absoluteLifetime.isZero() || absoluteLifetime.isNegative()) {
            throw new IllegalArgumentException("absoluteLifetime must be positive");
        }
        this.absoluteLifetime = absoluteLifetime;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && isExpired(session)) {
            session.invalidate();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":401,\"detail\":\"관리자 세션의 최대 유효 시간이 만료되었습니다.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return LOGIN_PATH.equals(request.getRequestURI());
    }

    private boolean isExpired(HttpSession session) {
        long ageMillis = clock.millis() - session.getCreationTime();
        return ageMillis >= absoluteLifetime.toMillis();
    }
}
