package com.home.application.user.port;

import com.home.domain.user.OAuthIdentityKey;

@FunctionalInterface
public interface IdentityLock {
    void lock(OAuthIdentityKey identity);
    static IdentityLock noop() { return ignored -> { }; }
}
