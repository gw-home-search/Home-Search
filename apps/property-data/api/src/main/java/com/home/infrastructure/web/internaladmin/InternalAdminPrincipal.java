package com.home.infrastructure.web.internaladmin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;

public record InternalAdminPrincipal(
        UUID accountId, String loginId, Set<String> roles, Set<String> permissions, String requestId) {
    public static final String REQUEST_ATTRIBUTE = InternalAdminPrincipal.class.getName();

    public InternalAdminPrincipal {
        if (accountId == null || loginId == null || loginId.isBlank() || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("internal admin actor claims are required");
        }
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        if (roles.isEmpty() || permissions.isEmpty()) {
            throw new IllegalArgumentException("internal admin authorization claims are required");
        }
    }

    public String actor() {
        return accountId.toString();
    }

    public void require(String permission) {
        if (!permissions.contains(permission)) throw new InternalAdminAuthorizationException();
    }

    public static InternalAdminPrincipal from(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (!(value instanceof InternalAdminPrincipal principal)) throw new InternalAdminAuthenticationException();
        return principal;
    }
}
