package com.home.infrastructure.web.internaladmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.security.jwt.JwtVerificationException;
import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;
import com.home.security.jwt.VerifiedJwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class InternalAdminJwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String INTERNAL_PREFIX = "/internal/v1/admin/";
    private final Rs256JwtCodec codec;
    private final JwtVerificationPolicy policy;
    private final ObjectMapper objectMapper;

    public InternalAdminJwtAuthenticationFilter(
            Rs256JwtCodec codec, JwtVerificationPolicy policy, ObjectMapper objectMapper) {
        this.codec = codec;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        InternalAdminPrincipal principal;
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
                throw new JwtVerificationException();
            }
            principal = principal(codec.verify(authorization.substring(7), policy));
            if (!principal.requestId().equals(request.getHeader("X-Request-Id"))) {
                throw new JwtVerificationException();
            }
        } catch (JwtVerificationException | IllegalArgumentException exception) {
            unauthorized(response);
            return;
        }
        request.setAttribute(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal);
        response.setHeader("X-Request-Id", principal.requestId());
        chain.doFilter(request, response);
    }

    private InternalAdminPrincipal principal(VerifiedJwt verified) {
        return new InternalAdminPrincipal(
                UUID.fromString(verified.subject()),
                stringClaim(verified.claims(), "loginId", 100),
                stringSet(verified.claims(), "roles"),
                stringSet(verified.claims(), "permissions"),
                stringClaim(verified.claims(), "requestId", 100));
    }

    private String stringClaim(Map<String, Object> claims, String name, int maximumLength) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximumLength) {
            throw new IllegalArgumentException("invalid internal claim");
        }
        return text;
    }

    private Set<String> stringSet(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Collection<?> values) || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException("invalid internal claim");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > 100) {
                throw new IllegalArgumentException("invalid internal claim");
            }
            result.add(text);
        }
        return Set.copyOf(result);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "type", "about:blank",
                        "title", "Unauthorized",
                        "status", HttpServletResponse.SC_UNAUTHORIZED,
                        "detail", "Internal admin authentication failed."));
    }
}
