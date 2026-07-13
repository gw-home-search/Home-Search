package com.home.user.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class UserJwtAuthenticationToken extends AbstractAuthenticationToken {
    private final AuthenticatedUserPrincipal principal;
    UserJwtAuthenticationToken(long userId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.principal = new AuthenticatedUserPrincipal(userId);
        setAuthenticated(true);
    }
    @Override public Object getCredentials() { return ""; }
    @Override public AuthenticatedUserPrincipal getPrincipal() { return principal; }
}
