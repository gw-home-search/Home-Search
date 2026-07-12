package com.home.infrastructure.web.internaladmin;

public final class InternalAdminAuthorizationException extends RuntimeException {
    public InternalAdminAuthorizationException() {
        super("internal admin permission denied");
    }
}
