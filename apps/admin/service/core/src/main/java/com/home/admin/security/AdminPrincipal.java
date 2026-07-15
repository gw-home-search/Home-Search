package com.home.admin.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;

public record AdminPrincipal(
        UUID accountId, String loginId, String displayName, Set<String> roles, Set<String> permissions)
        implements Serializable, Principal {
    @Override
    public String getName() {
        return loginId;
    }
}
