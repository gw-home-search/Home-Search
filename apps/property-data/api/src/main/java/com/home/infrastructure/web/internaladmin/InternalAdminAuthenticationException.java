package com.home.infrastructure.web.internaladmin;

public final class InternalAdminAuthenticationException extends RuntimeException {
    public InternalAdminAuthenticationException() {
        super("internal admin authentication required");
    }
}
